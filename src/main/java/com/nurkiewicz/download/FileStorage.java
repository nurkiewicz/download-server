package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;

@Component
public class FileStorage {

	private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

	public Optional<FilePointer> findFile(UUID uuid) {
		log.debug("Downloading {}", uuid);
		final URL resource = getClass().getResource("/download.txt");
		final File file = new File(resource.getFile());
		final FileSystemPointer pointer = new FileSystemPointer(file);
		return Optional.of(pointer);
	}

}

