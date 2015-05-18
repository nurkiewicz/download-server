package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpHeaders.IF_MODIFIED_SINCE;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NOT_MODIFIED;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.web.bind.annotation.RequestMethod.GET;

@RestController
@RequestMapping("/download")
public class DownloadController {

	private static final Logger log = LoggerFactory.getLogger(DownloadController.class);

	private final FileStorage storage;

	@Autowired
	public DownloadController(FileStorage storage) {
		this.storage = storage;
	}

	@RequestMapping(method = GET, value = "/{uuid}")
	public ResponseEntity<Resource> download(
			@PathVariable UUID uuid,
			@RequestHeader(IF_NONE_MATCH) Optional<String> requestEtagOpt,
			@RequestHeader(IF_MODIFIED_SINCE) Optional<Date> ifModifiedSinceOpt
			) {
		return storage
				.findFile(uuid)
				.map(pointer -> prepareResponse(
						pointer,
						requestEtagOpt,
						ifModifiedSinceOpt.map(Date::toInstant)))
				.orElseGet(() -> new ResponseEntity<>(NOT_FOUND));
	}

	private ResponseEntity<Resource> prepareResponse(FilePointer filePointer, Optional<String> requestEtagOpt, Optional<Instant> ifModifiedSinceOpt) {
		if (requestEtagOpt.isPresent()) {
			final String requestEtag = requestEtagOpt.get();
			if (filePointer.matchesEtag(requestEtag)) {
				return notModified(filePointer);
			}
		}
		if (ifModifiedSinceOpt.isPresent()) {
			final Instant isModifiedSince = ifModifiedSinceOpt.get();
			if (filePointer.modifiedAfter(isModifiedSince)) {
				return notModified(filePointer);
			}
		}
		return serveDownload(filePointer);
	}

	private ResponseEntity<Resource> serveDownload(FilePointer filePointer) {
		log.debug("Serving '{}'", filePointer);
		final InputStream inputStream = filePointer.open();
		final InputStreamResource resource = new InputStreamResource(inputStream);
		return response(filePointer, OK, resource);
	}

	private ResponseEntity<Resource> notModified(FilePointer filePointer) {
		log.trace("Cached on client side {}, returning 304", filePointer);
		return response(filePointer, NOT_MODIFIED, null);
	}

	private ResponseEntity<Resource> response(FilePointer filePointer, HttpStatus status, Resource body) {
		return ResponseEntity
				.status(status)
				.eTag(filePointer.getEtag())
				.lastModified(filePointer.getLastModified().toEpochMilli()).body(body);
	}

}
