package com.nurkiewicz.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

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
	public ResponseEntity<Resource> download(@PathVariable UUID uuid) {
		return storage
				.findFile(uuid)
				.map(this::prepareResponse)
				.orElseGet(this::notFound);
	}

	private ResponseEntity<Resource> prepareResponse(FilePointer filePointer) {
		log.debug("Serving {}", filePointer);
		final InputStream inputStream = filePointer.open();
		final InputStreamResource resource = new InputStreamResource(inputStream);
		return ResponseEntity.ok(resource);
	}

	private ResponseEntity<Resource> notFound() {
		return new ResponseEntity<>((Resource)null, HttpStatus.NOT_FOUND);
	}
}
