package com.nurkiewicz.download;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.apache.commons.io.FileUtils.ONE_MB;
import static org.springframework.http.HttpStatus.*;

public class ExistingFile {

	private static final Logger log = LoggerFactory.getLogger(ExistingFile.class);

	private final HttpMethod method;
	private final FilePointer filePointer;
	private final UUID uuid;

	public ExistingFile(HttpMethod method, FilePointer filePointer, UUID uuid) {
		this.method = method;
		this.filePointer = filePointer;
		this.uuid = uuid;
	}

	public ResponseEntity<Resource> redirect(Optional<String> requestEtagOpt, Optional<Date> ifModifiedSinceOpt) {
		return serveWithCaching(requestEtagOpt, ifModifiedSinceOpt, this::redirectDownload);
	}

	public ResponseEntity<Resource> handle(Optional<String> requestEtagOpt, Optional<Date> ifModifiedSinceOpt) {
		return serveWithCaching(requestEtagOpt, ifModifiedSinceOpt, this::serveDownload);
	}

	private ResponseEntity<Resource> serveWithCaching(
			Optional<String> requestEtagOpt, Optional<Date> ifModifiedSinceOpt,
			Function<FilePointer, ResponseEntity<Resource>> notCachedResponse) {
		if (cached(requestEtagOpt, ifModifiedSinceOpt))
			return notModified(filePointer);
		return notCachedResponse.apply(filePointer);
	}

	private boolean cached(Optional<String> requestEtagOpt, Optional<Date> ifModifiedSinceOpt) {
		final boolean matchingEtag = requestEtagOpt
				.map(filePointer::matchesEtag)
				.orElse(false);
		final boolean notModifiedSince = ifModifiedSinceOpt
				.map(Date::toInstant)
				.map(filePointer::modifiedAfter)
				.orElse(false);
		return matchingEtag || notModifiedSince;
	}

	private ResponseEntity<Resource> redirectDownload(FilePointer filePointer) {
		try {
			log.trace("Redirecting {} '{}'", method, filePointer);
			return ResponseEntity
					.status(MOVED_PERMANENTLY)
					.location(new URI("/download/" + uuid + "/" + filePointer.getOriginalName()))
					.body(null);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
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
		final RateLimiter throttler = RateLimiter.create(ONE_MB);
		final ThrottlingInputStream throttlingInputStream = new ThrottlingInputStream(inputStream, throttler);
		return new InputStreamResource(throttlingInputStream);
	}

	private ResponseEntity<Resource> notModified(FilePointer filePointer) {
		log.trace("Cached on client side {}, returning 304", filePointer);
		return response(filePointer, NOT_MODIFIED, null);
	}

	private ResponseEntity<Resource> response(FilePointer filePointer, HttpStatus status, Resource body) {
		final ResponseEntity.BodyBuilder responseBuilder = ResponseEntity
				.status(status)
				.eTag(filePointer.getEtag())
				.contentLength(filePointer.getSize())
				.lastModified(filePointer.getLastModified().toEpochMilli());
		filePointer
				.getMediaType()
				.map(this::toMediaType)
				.ifPresent(responseBuilder::contentType);
		return responseBuilder.body(body);
	}

	private MediaType toMediaType(com.google.common.net.MediaType input) {
		return input.charset()
				.transform(c -> new MediaType(input.type(), input.subtype(), c))
				.or(new MediaType(input.type(), input.subtype()));
	}

}
