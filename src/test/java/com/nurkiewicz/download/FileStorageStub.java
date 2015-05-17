package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;

@Component
@Profile("test")
public class FileStorageStub implements FileStorage {

	private static final Logger log = LoggerFactory.getLogger(FileStorageStub.class);

	public static final UUID TXT_FILE = UUID.randomUUID();
	public static final UUID NOT_FOUND = UUID.randomUUID();


	public Optional<FilePointer> findFile(UUID uuid) {
		log.debug("Downloading {}", uuid);
		if (uuid.equals(TXT_FILE)) {
			final URL resource = getClass().getResource("/download.txt");
			final File file = new File(resource.getFile());
			final FileSystemPointer pointer = new FileSystemPointer(file);
			return Optional.of(pointer);
		}
		return Optional.empty();
	}
}
