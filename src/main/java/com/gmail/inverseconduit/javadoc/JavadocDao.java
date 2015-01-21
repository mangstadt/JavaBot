package com.gmail.inverseconduit.javadoc;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.sun.nio.file.SensitivityWatchEventModifier;

/**
 * Retrieves class information from Javadoc ZIP files, generated by
 * OakbotDoclet.
 * @author Michael Angstadt
 */
public class JavadocDao {
	private static final Logger logger = Logger.getLogger(JavadocDao.class.getName());

	/**
	 * Maps each Javadoc ZIP file to the list of classes it contains.
	 */
	private final Multimap<LibraryZipFile, String> libraryClasses = HashMultimap.create();

	/**
	 * Maps class name aliases to their fully qualified names. For example, maps
	 * "string" to "java.lang.String". Note that there can be more than one
	 * class name mapped to an alias (for example "list" is mapped to
	 * "java.util.List" and "java.awt.List").
	 */
	private final Multimap<String, String> aliases = HashMultimap.create();

	/**
	 * Caches class info that was parsed from a Javadoc ZIP file. The key is the
	 * fully-qualified name of the class, and the value is the parsed Javadoc
	 * info.
	 */
	private final Map<String, ClassInfo> cache = new HashMap<>();

	/**
	 * @param dir the path to where the Javadoc ZIP files are stored.
	 * @throws IOException if there's a problem reading the ZIP files
	 */
	public JavadocDao(Path dir) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, JavadocDao::isZipFile)) {
			for (Path path : stream) {
				addApi(path);
			}
		}

		WatchThread watchThread = new WatchThread(dir);
		watchThread.start();
	}

	/**
	 * Adds a Javadoc ZIP file to the DAO.
	 * @param zipFile the zip file (generated by OakbotDoclet)
	 * @throws IOException if there was a problem reading the ZIP file
	 */
	private void addApi(Path zipFile) throws IOException {
		//add all the class names to the simple name index
		LibraryZipFile zip = new LibraryZipFile(zipFile);
		Iterator<ClassName> it = zip.getClasses();
		synchronized (this) {
			while (it.hasNext()) {
				ClassName className = it.next();
				String fullName = className.getFull();
				String simpleName = className.getSimple();

				aliases.put(simpleName.toLowerCase(), fullName);
				aliases.put(simpleName, fullName);
				aliases.put(fullName.toLowerCase(), fullName);
				aliases.put(fullName, fullName);
				libraryClasses.put(zip, fullName);
			}
		}
	}

	/**
	 * Gets the documentation on a class.
	 * @param className a fully-qualified class name (e.g. "java.lang.String")
	 * or a simple class name (e.g. "String").
	 * @return the class documentation or null if the class was not found
	 * @throws IOException if there's a problem reading the class's Javadocs
	 * @throws MultipleClassesFoundException if a simple name was passed into
	 * this method and multiple classes were found that have that name
	 */
	public synchronized ClassInfo getClassInfo(String className) throws IOException, MultipleClassesFoundException {
		Collection<String> names = aliases.get(className);
		if (names.isEmpty()) {
			//try case-insensitive search
			names = aliases.get(className.toLowerCase());
		}

		if (names.isEmpty()) {
			//no class found
			return null;
		}

		if (names.size() > 1) {
			//multiple classes found
			throw new MultipleClassesFoundException(names);
		}

		className = names.iterator().next();

		//check the cache
		ClassInfo info = cache.get(className);
		if (info != null) {
			return info;
		}

		//parse the class info from the Javadocs
		for (LibraryZipFile zip : libraryClasses.keys()) {
			info = zip.getClassInfo(className);
			if (info != null) {
				cache.put(className, info);
				return info;
			}
		}

		return null;
	}

	private class WatchThread extends Thread {
		private final Path dir;
		private final WatchService watcher;

		/**
		 * @param dir the directory to watch
		 * @throws IOException if there's a problem watching the directory
		 */
		public WatchThread(Path dir) throws IOException {
			setName(getClass().getSimpleName());
			setDaemon(true);

			this.dir = dir;
			watcher = FileSystems.getDefault().newWatchService();
			dir.register(watcher, new WatchEvent.Kind[] { StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY }, SensitivityWatchEventModifier.HIGH);
		}

		@Override
		public void run() {
			while (true) {
				WatchKey key;
				try {
					key = watcher.take();
				} catch (InterruptedException e) {
					return;
				}

				for (WatchEvent<?> event : key.pollEvents()) {
					WatchEvent.Kind<?> kind = event.kind();
					if (kind == StandardWatchEventKinds.OVERFLOW) {
						continue;
					}

					@SuppressWarnings("unchecked")
					Path file = ((WatchEvent<Path>) event).context();
					if (!isZipFile(file)) {
						continue;
					}

					file = dir.resolve(file);

					if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
						add(file);
						continue;
					}

					if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
						remove(file);
						continue;
					}

					if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
						remove(file);
						add(file);
						continue;
					}
				}

				boolean valid = key.reset();
				if (!valid) {
					break;
				}
			}
		}

		private void add(Path file) {
			logger.info("Loading ZIP file " + file + "...");
			try {
				addApi(file);
				logger.info("ZIP file " + file + " loaded.");
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Could not parse Javadoc ZIP file.  ZIP file was not added to the JavadocDao.", e);
			}
		}

		private void remove(Path file) {
			logger.info("Removing ZIP file " + file + "...");
			Path fileName = file.getFileName();

			synchronized (JavadocDao.this) {
				//find the corresponding LibraryZipFile object
				LibraryZipFile found = null;
				for (LibraryZipFile zip : libraryClasses.keys()) {
					if (zip.getPath().getFileName().equals(fileName)) {
						found = zip;
						break;
					}
				}
				if (found == null) {
					logger.warning("Tried to remove ZIP file \"" + file + "\", but it was not found in the JavadocDao.");
					return;
				}

				Collection<String> classNames = libraryClasses.removeAll(found);
				aliases.values().removeAll(classNames);
				cache.keySet().removeAll(classNames);
			}

			logger.info("ZIP file " + file + " removed.");
		}
	}

	private static boolean isZipFile(Path file) {
		return file.getFileName().toString().toLowerCase().endsWith(".zip");
	}
}
