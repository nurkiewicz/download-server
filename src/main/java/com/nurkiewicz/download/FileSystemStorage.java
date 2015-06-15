package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;

/**
 * Stub implementation that for any UUID returns `logback.xml` file.
 * Replace with real implementation.
 */
@Component
@Profile("!test")
public class FileSystemStorage implements FileStorage {

	private static final Logger log = LoggerFactory.getLogger(FileSystemStorage.class);

	@Override
	public Optional<FilePointer> findFile(UUID uuid) {
		log.debug("Downloading {}", uuid);
		final URL resource = getClass().getResource("/logback.xml");
		final File file = new File(resource.getFile());
		final FileSystemPointer pointer = new FileSystemPointer(file);
		return Optional.of(pointer);
	}

}

