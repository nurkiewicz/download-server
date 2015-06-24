# Writing a download server. Part IV: Implement `HEAD` operation (efficiently)

`HEAD` is an often forgotten HTTP method (verb) that behaves just like GET, but does not return body. You use HEAD in order to check the existence of a resource (it should return 404 in case of absence) and make sure you don't have a stale version in your cache. In that case you expect `304 Not Modified`, while 200 means the server has more recent version. You can e.g. use HEAD to efficiently implement software updates. In that case `ETag` is your application version (build, tag, commit hash) and you have a fixed `/most_recent` endpoint. Your software sends HEAD request with current version in `ETag`. If there were no updates, server will reply with 304. In case of 200 you can ask user whether she wants to upgrade without downloading the software yet. Finally requesting `GET /most_recent` will always download the most recent version of your software. The power of HTTP!

In servlets `HEAD` is implemented by default in `doHead()` which you are suppose to override. The default implementation just delegates to `GET` but discards body. This can't be efficient, especially when you load your resources from outside, like Amazon S3. Luckily (?) Spring MVC doesn't implement HEAD by default, so you have to do it manually. Let's start from few integration tests of HEAD:

	def 'should return 200 OK on HEAD request, but without body'() {
		expect:
			mockMvc
				.perform(
					head('/download/' + FileExamples.TXT_FILE_UUID))
				.andExpect(
						status().isOk())
				.andExpect(
						content().bytes(new byte[0]))
	}

	def 'should return 304 on HEAD request if we have cached version'() {
		expect:
			mockMvc
				.perform(
					head('/download/' + FileExamples.TXT_FILE_UUID)
							.header(IF_NONE_MATCH, FileExamples.TXT_FILE.getEtag()))
				.andExpect(
					status().isNotModified())
				.andExpect(
					header().string(ETAG, FileExamples.TXT_FILE.getEtag()))
	}

	def 'should return Content-length header'() {
		expect:
			mockMvc
				.perform(
					head('/download/' + FileExamples.TXT_FILE_UUID))
				.andExpect(
					status().isOk())
				.andExpect(
					header().longValue(CONTENT_LENGTH, FileExamples.TXT_FILE.size))
	}

The actual implementation is quite straightforward, but requires a bit of refactoring in order to avoid duplication. Download endpoint now accepts both GET and HEAD:

	@RequestMapping(method = {GET, HEAD}, value = "/{uuid}")
	public ResponseEntity<Resource> download(
			HttpMethod method,
			@PathVariable UUID uuid,
			@RequestHeader(IF_NONE_MATCH) Optional<String> requestEtagOpt,
			@RequestHeader(IF_MODIFIED_SINCE) Optional<Date> ifModifiedSinceOpt
			) {
		return storage
				.findFile(uuid)
				.map(pointer -> new ExistingFile(method, pointer))
				.map(file -> file.handle(requestEtagOpt, ifModifiedSinceOpt))
				.orElseGet(() -> new ResponseEntity<>(NOT_FOUND));
	}

I created a new abstraction `ExistingFile`, which encapsulates found `FilePointer` and HTTP verb we invoke on it. `ExistingFile.handle()` has all what it takes to serve file or just metadata via HEAD:


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
					.lastModified(filePointer.getLastModified().toEpochMilli())
					.body(body);
		}

	}

`resourceToReturn()` is crucial. If it returns `null`, Spring MVC will not include any body in response. Everything else remains the same (response headers, etc.)


---

## Writing a download server

* Part I: Always stream, never keep fully in memory
* Part II: headers: Last-Modified, ETag and If-None-Match
* Part III: headers: Content-length and Range
* Part IV: Implement `HEAD` operation (efficiently)
* Part V: Throttle download speed
* Part VI: Describe what you send (Content-type, et.al.)

The [sample application](https://github.com/nurkiewicz/download-server) developed throughout these articles is available on GitHub.