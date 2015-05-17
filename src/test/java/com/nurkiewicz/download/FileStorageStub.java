package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

import static com.nurkiewicz.download.FileExamples.TXT_FILE;
import static com.nurkiewicz.download.FileExamples.TXT_FILE_UUID;

@Component
@Profile("test")
public class FileStorageStub implements FileStorage {

	private static final Logger log = LoggerFactory.getLogger(FileStorageStub.class);

	public Optional<FilePointer> findFile(UUID uuid) {
		log.debug("Downloading {}", uuid);
		if (uuid.equals(TXT_FILE_UUID)) {
			return Optional.of(TXT_FILE);
		}
		return Optional.empty();
	}
}
