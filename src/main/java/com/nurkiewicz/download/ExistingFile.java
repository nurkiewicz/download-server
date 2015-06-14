package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.springframework.http.HttpStatus.NOT_MODIFIED;
import static org.springframework.http.HttpStatus.OK;

public class ExistingFile {

	private static final Logger log = LoggerFactory.getLogger(ExistingFile.class);

	private final HttpMethod method;
	private final FilePointer filePointer;

	public ExistingFile(HttpMethod method, FilePointer filePointer) {
		this.method = method;
		this.filePointer = filePointer;
	}

	public ResponseEntity<Resource> handle(Optional<String> requestEtagOpt, Optional<Date> ifModifiedSinceOpt) {
		if (requestEtagOpt.isPresent()) {
			final String requestEtag = requestEtagOpt.get();
			if (filePointer.matchesEtag(requestEtag)) {
				return notModified(filePointer);
			}
		}
		if (ifModifiedSinceOpt.isPresent()) {
			final Instant isModifiedSince = ifModifiedSinceOpt.get().toInstant();
			if (filePointer.modifiedAfter(isModifiedSince)) {
				return notModified(filePointer);
			}
		}
		return serveDownload(filePointer);
	}

	private ResponseEntity<Resource> serveDownload(FilePointer filePointer) {
		log.debug("Serving {} '{}'", method, filePointer);
		final InputStreamResource resource = resourceToReturn(filePointer);
		return response(filePointer, OK, resource);
	}

	private InputStreamResource resourceToReturn(FilePointer filePointer) {
		if (method == HttpMethod.GET)
			return buildResource(filePointer);
		else
			return null;
	}

	private InputStreamResource buildResource(FilePointer filePointer) {
		final InputStream inputStream = filePointer.open();
		return new InputStreamResource(inputStream);
	}

	private ResponseEntity<Resource> notModified(FilePointer filePointer) {
		log.trace("Cached on client side {}, returning 304", filePointer);
		return response(filePointer, NOT_MODIFIED, null);
	}

	private ResponseEntity<Resource> response(FilePointer filePointer, HttpStatus status, Resource body) {
		return ResponseEntity
				.status(status)
				.eTag(filePointer.getEtag())
				.contentLength(filePointer.getSize())
				.lastModified(filePointer.getLastModified().toEpochMilli())
				.body(body);
	}

}
