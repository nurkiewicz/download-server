# Writing a download server. Part VI: Describe what you send (Content-type, et.al.)

As far as HTTP is concerned, what client is downloading is just a bunch of bytes. However client would really like to know how to interpret these bytes. Is it an image? Or maybe a ZIP file? The last part of this series describes how to give a hint to the client what she downloads.

# Set `Content-type` response header

Content type describes [MIME type](https://en.wikipedia.org/wiki/Internet_media_type) of resource being returned. This header instructs web browser how to treat stream of bytes flowing from the download server. Without this header browser is clueless of what it actually received and simply displays content as if it was a text file. Needless to say binary PDF (see screenshot above), image or video displayed like a text file doesn't look good. The hardest part is to actually obtain media type somehow. Luckily Java itself has a tool for guessing media type based on extension and/or contents of the resource:

	import com.google.common.net.MediaType;
	import java.io.*;
	import java.time.Instant;

	public class FileSystemPointer implements FilePointer {

		private final MediaType mediaTypeOrNull;

		public FileSystemPointer(File target) {
			final String contentType = java.nio.file.Files.probeContentType(target.toPath());
			this.mediaTypeOrNull = contentType != null ?
					MediaType.parse(contentType) :
					null;
		}

Note that it's not idiomatic to use `Optional<T>` as a class field, because it's not `Serializable` and we avoid potential issues. Knowing the media type we must return it in the response. Notice that this small snippet of code uses both `Optional` from JDK 8 and Guava, as well as `MediaType` class from both Spring framework and Guava. What a type system mess!

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

	@Override
	public Optional<MediaType> getMediaType() {
		return Optional.ofNullable(mediaTypeOrNull);
	}


# 9. Preserve original file name and extension

While `Content-type` works great when you open a document straight in a web browser, imagine your user stores this document on disk. Whether the browser decides to display or store a downloaded file is beyond the scope of this article - but we should be prepared for both. If browser simply stores file on disk, it has to save it under some name. Firefox by default will use the last part of URL, which happens to be UUID of the resource in our case. Not very user friendly. Chrome is a bit better - knowing the MIME type from `Content-type` header it will heuristically add appropriate extension, e.g. `.zip` in case of `application/zip`. But still the file name is a random UUID, while what the user uploaded might have been `cats.zip`. Thus if you are aiming toward browsers and not automated clients, it would be desirable to use real name as last part of the URL. We still want to use UUIDs to distinguish between resources internally, avoid collision and not expose our internal storage structure. But externally we can redirect to user-friendly URL, but keeping UUID for safety. First of all we need one extra endpoint:

	@RequestMapping(method = {GET, HEAD}, value = "/{uuid}")
	public ResponseEntity<Resource> redirect(
			HttpMethod method,
			@PathVariable UUID uuid,
			@RequestHeader(IF_NONE_MATCH) Optional<String> requestEtagOpt,
			@RequestHeader(IF_MODIFIED_SINCE) Optional<Date> ifModifiedSinceOpt
			) {
		return findExistingFile(method, uuid)
				.map(file -> file.redirect(requestEtagOpt, ifModifiedSinceOpt))
				.orElseGet(() -> new ResponseEntity<>(NOT_FOUND));
	}

	@RequestMapping(method = {GET, HEAD}, value = "/{uuid}/{filename}")
	public ResponseEntity<Resource> download(
			HttpMethod method,
			@PathVariable UUID uuid,
			@RequestHeader(IF_NONE_MATCH) Optional<String> requestEtagOpt,
			@RequestHeader(IF_MODIFIED_SINCE) Optional<Date> ifModifiedSinceOpt
			) {
		return findExistingFile(method, uuid)
				.map(file -> file.handle(requestEtagOpt, ifModifiedSinceOpt))
				.orElseGet(() -> new ResponseEntity<>(NOT_FOUND));
	}

	private Optional<ExistingFile> findExistingFile(HttpMethod method, @PathVariable UUID uuid) {
		return storage
				.findFile(uuid)
				.map(pointer -> new ExistingFile(method, pointer, uuid));
	}

If you look closely, `{filename}` is not even used, it's merely a hint for the browser. If you want extra security, you might compare supplied file name with the one mapped to given `UUID`. What's really important here is that just asking for `UUID` will redirect us:

	$ curl -v localhost:8080/download/4a8883b6-ead6-4b9e-8979-85f9846cab4b
	> GET /download/4a8883b6-ead6-4b9e-8979-85f9846cab4b HTTP/1.1
	...
	< HTTP/1.1 301 Moved Permanently
	< Location: /download/4a8883b6-ead6-4b9e-8979-85f9846cab4b/cats.zip

And you need one extra network trip to fetch actual file:

	> GET /download/4a8883b6-ead6-4b9e-8979-85f9846cab4b/cats.zip HTTP/1.1
	...
	> 
	HTTP/1.1 200 OK
	< ETag: "be20c3b1...fb1a4"
	< Last-Modified: Thu, 21 Aug 2014 22:44:37 GMT
	< Content-Type: application/zip;charset=UTF-8
	< Content-Length: 489455

The implementation is straightforward, but it was refactored a bit to avoid duplication:

	public ResponseEntity<Resource> redirect(Optional<String> requestEtagOpt, Optional<Date> ifModifiedSinceOpt) {
		if (cached(requestEtagOpt, ifModifiedSinceOpt))
			return notModified(filePointer);
		return redirectDownload(filePointer);
	}

	public ResponseEntity<Resource> handle(Optional<String> requestEtagOpt, Optional<Date> ifModifiedSinceOpt) {
		if (cached(requestEtagOpt, ifModifiedSinceOpt))
			return notModified(filePointer);
		return serveDownload(filePointer);
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

You can even go further with higher-order functions to avoid little duplication:

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

Obviously one extra redirect is an extra cost one must pay for each download, so it's a trade-off. You may consider heuristics based on `User-agent` (redirect if browser, server directly if automated client) to avoid redirect in case of non-human clients. This concludes our series about file downloading. The emerge of HTTP/2 will definitely bring more improvements and techniques, like prioritizing.


---

## Writing a download server

* Part I: Always stream, never keep fully in memory
* Part II: headers: Last-Modified, ETag and If-None-Match
* Part III: headers: Content-length and Range
* Part IV: Implement `HEAD` operation (efficiently)
* Part V: Throttle download speed
* Part VI: Describe what you send (Content-type, et.al.)

The [sample application](https://github.com/nurkiewicz/download-server) developed throughout these articles is available on GitHub.