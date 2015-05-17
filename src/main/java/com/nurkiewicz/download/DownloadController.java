package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

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
			@RequestHeader(IF_NONE_MATCH) Optional<String> requestEtagOpt) {
		return storage
				.findFile(uuid)
				.map(pointer -> prepareResponse(pointer, requestEtagOpt))
				.orElseGet(() -> new ResponseEntity<>(NOT_FOUND));
	}

	private ResponseEntity<Resource> prepareResponse(FilePointer filePointer, Optional<String> requestEtagOpt) {
		return requestEtagOpt
				.filter(filePointer::matchesEtag)
				.map(this::notModified)
				.orElseGet(() -> serveDownload(filePointer));
	}

	private ResponseEntity<Resource> serveDownload(FilePointer filePointer) {
		log.debug("Serving '{}'", filePointer);
		final InputStream inputStream = filePointer.open();
		final InputStreamResource resource = new InputStreamResource(inputStream);
		return ResponseEntity
				.status(OK)
				.eTag(filePointer.getEtag())
				.body(resource);
	}

	private ResponseEntity<Resource> notModified(String etag) {
		log.trace("Cached on client side {}, returning 304", etag);
		return ResponseEntity
				.status(NOT_MODIFIED)
				.eTag(etag)
				.body(null);
	}

}
