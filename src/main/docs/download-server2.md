# Writing a download server. Part II: headers: Last-Modified, ETag and If-None-Match

Caching on the client side is one of the foundations of World Wide Web. Server should inform client about validity of resources and client should cache them as eagerly as possible. Without caching the web as we see it would be insanely slow. Just hit `Ctrl` + `F5` on any website and compare it with ordinary `F5` - the latter is much faster as it uses already cached resources. Caching is also important for downloading. If we already fetched several megabytes of data and they haven't changed, pushing them through network is quite wasteful.

# Use `ETag` and `If-None-Match` headers

HTTP `ETag` header can be used to avoid repeatable downloads of resources client already has. Along with first response server returns an `ETag` header, which is typically a hash value of the contents of a file. Client can keep `ETag` and send it (in `If-None-Match` request header) when requesting the same resource later. If it wasn't changed in the meantime, server can simply return `304 Not Modified` response. Let's start with an integration test for `ETag` support:

	def 'should send file if ETag not present'() {
		expect:
			mockMvc
					.perform(
						get('/download/' + FileExamples.TXT_FILE_UUID))
					.andExpect(
						status().isOk())
		}

	def 'should send file if ETag present but not matching'() {
		expect:
			mockMvc
					.perform(
						get('/download/' + FileExamples.TXT_FILE_UUID)
								.header(IF_NONE_MATCH, '"WHATEVER"'))
					.andExpect(
						status().isOk())
	}

	def 'should not send file if ETag matches content'() {
		given:
			String etag = FileExamples.TXT_FILE.getEtag()
		expect:
			mockMvc
					.perform(
						get('/download/' + FileExamples.TXT_FILE_UUID)
								.header(IF_NONE_MATCH, etag))
					.andExpect(
						status().isNotModified())
					.andExpect(
						header().string(ETAG, etag))
	}

Interestingly there is a built-in [`ShallowEtagHeaderFilter`](http://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/web/filter/ShallowEtagHeaderFilter.html) in Spring framework. Installing it makes all the tests pass, including the last one:

	@WebAppConfiguration
	@ContextConfiguration(classes = [MainApplication])
	@ActiveProfiles("test")
	class DownloadControllerSpec extends Specification {

		private MockMvc mockMvc

		@Autowired
		public void setWebApplicationContext(WebApplicationContext wac) {
			mockMvc = MockMvcBuilders.webAppContextSetup(wac)
					.addFilter(new Sha512ShallowEtagHeaderFilter(), "/download/*")
					.build()
		}

		//tests...

	}

I actually plug in my own `Sha512ShallowEtagHeaderFilter` that uses [SHA-512](http://en.wikipedia.org/wiki/SHA-2) instead of default [MD5](http://en.wikipedia.org/wiki/MD5). Also the default implementation for some reason prepends `0` in front of hash:

	public class ShallowEtagHeaderFilter {
		protected String generateETagHeaderValue(byte[] bytes) {
			StringBuilder builder = new StringBuilder("\"0");
			DigestUtils.appendMd5DigestAsHex(bytes, builder);
			builder.append('"');
			return builder.toString();
		}

		//...
	}

vs.:

	public class Sha512ShallowEtagHeaderFilter extends ShallowEtagHeaderFilter {

		@Override
		protected String generateETagHeaderValue(byte[] bytes) {
			final HashCode hash = Hashing.sha512().hashBytes(bytes);
			return "\"" + hash + "\"";
		}
	}

Unfortunately we cannot use built-in filters in our case as they must first fully read response body in order to compute `ETag`. This basically turns off body streaming introduced in previous article - whole response is stored in memory. We must implement `ETag` functionality ourselves. Technically `If-None-Match` can include multiple `ETag` values. However neither Google Chrome nor `ShallowEtagHeaderFilter` support it, so we will skip that as well. In order to control response headers we now return `ResponseEntity<Resource>`:

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

	private ResponseEntity<Resource> notModified(String etag) {
		log.trace("Cached on client side {}, returning 304", etag);
		return ResponseEntity
				.status(NOT_MODIFIED)
				.eTag(etag)
				.body(null);
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

The process is controlled by optional `requestEtagOpt`. If it's present and matches whatever was sent by the client, we return 304. Otherwise 200 OK as usual. New methods in `FilePointer` introduced in this example look as follows:


	import com.google.common.hash.HashCode;
	import com.google.common.hash.Hashing;
	import com.google.common.io.Files;

	public class FileSystemPointer implements FilePointer {

		private final File target;
		private final HashCode tag;

		public FileSystemPointer(File target) {
			try {
				this.target = target;
				this.tag = Files.hash(target, Hashing.sha512());
			} catch (IOException e) {
				throw new IllegalArgumentException(e);
			}
		}

		@Override
		public InputStream open() {
			try {
				return new BufferedInputStream(new FileInputStream(target));
			} catch (FileNotFoundException e) {
				throw new IllegalArgumentException(e);
			}
		}

		@Override
		public String getEtag() {
			return "\"" + tag + "\"";
		}

		@Override
		public boolean matchesEtag(String requestEtag) {
			return getEtag().equals(requestEtag);
		}
	}

Here you see `FileSystemPointer` implementation that reads files straight from file system. The crucial part is to cache tag instead of recalculating it on every request. The implementation above behaves as expected, for example web browsers won't download the resource again.

# 3. Use `Last-Modified` header

Similar to `ETag` and `If-None-Match` headers there are `Last-Modified` and `If-Modified-Since`. I guess they are pretty self-explanatory: first server returns `Last-Modified` response header indicating when a given resource was last modified (*duh!*). Client caches this timestamp and passes it along with subsequent request to the same resource in `If-Modified-Since` request header. If the resource wasn't changed in the meantime, server will respond with 304, saving bandwidth. This is a fallback mechanism and it's a good practice to implement both `ETag`s and `Last-Modified`. Let's start with integration tests:

	def 'should not return file if wasn\'t modified recently'() {
		given:
			Instant lastModified = FileExamples.TXT_FILE.getLastModified()
			String dateHeader = toDateHeader(lastModified)
		expect:
			mockMvc
					.perform(
					get('/download/' + FileExamples.TXT_FILE_UUID)
							.header(IF_MODIFIED_SINCE, dateHeader))
					.andExpect(
							status().isNotModified())
	}

	def 'should not return file if server has older version than the client'() {
		given:
			Instant lastModifiedLaterThanServer = FileExamples.TXT_FILE.getLastModified().plusSeconds(60)
			String dateHeader = toDateHeader(lastModifiedLaterThanServer)
		expect:
			mockMvc
					.perform(
					get('/download/' + FileExamples.TXT_FILE_UUID)
							.header(IF_MODIFIED_SINCE, dateHeader))
					.andExpect(
							status().isNotModified())
	}

	def 'should return file if was modified after last retrieval'() {
		given:
			Instant lastModifiedRecently = FileExamples.TXT_FILE.getLastModified().minusSeconds(60)
			String dateHeader = toDateHeader(lastModifiedRecently)
		expect:
			mockMvc
					.perform(
					get('/download/' + FileExamples.TXT_FILE_UUID)
							.header(IF_MODIFIED_SINCE, dateHeader))
					.andExpect(
							status().isOk())
	}

	private static String toDateHeader(Instant lastModified) {
		ZonedDateTime dateTime = ZonedDateTime.ofInstant(lastModified, ZoneOffset.UTC)
		DateTimeFormatter.RFC_1123_DATE_TIME.format(dateTime)
	}

And the implementation:

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

Sadly using `Optional` idiomatically no longer looks good so I stick to `isPresent()`. We check both `If-Modified-Since` and `If-None-Match`. If neither match, we serve file as usual. Just to give you a taste of how these headers work, let's execute few end-to-end tests. First request:

	> GET /download/4a8883b6-ead6-4b9e-8979-85f9846cab4b HTTP/1.1
	> ...
	> 
	< HTTP/1.1 200 OK
	< ETag: "8b97c678a7f1d2e0af...921228d8e"
	< Last-Modified: Sun, 17 May 2015 15:45:26 GMT
	< ...

Subsequent request with `ETag` (shortened):

	> GET /download/4a8883b6-ead6-4b9e-8979-85f9846cab4b HTTP/1.1
	> If-None-Match: "8b97c678a7f1d2e0af...921228d8e"
	> ...
	> 
	< HTTP/1.1 304 Not Modified
	< ETag: "8b97c678a7f1d2e0af...921228d8e"
	< Last-Modified: Sun, 17 May 2015 15:45:26 GMT
	< ...

And in case our client supports `Last-Modified` only:

	> GET /download/4a8883b6-ead6-4b9e-8979-85f9846cab4b HTTP/1.1
	> If-Modified-Since: Tue, 19 May 2015 06:59:55 GMT
	> ...
	> 
	< HTTP/1.1 304 Not Modified
	< ETag: "8b97c678a7f1d2e0af9cda473b36c21f1b68e35b93fec2eb5c38d182c7e8f43a069885ec56e127c2588f9495011fd8ce032825b6d3136df7adbaa1f921228d8e"
	< Last-Modified: Sun, 17 May 2015 15:45:26 GMT

There are many built-in tools such as filter that can handle caching for you. However if you need to be sure your files are streamed rather then pre-buffered on the server side, extra care needs to be taken.


---

## Writing a download server

* Part I: Always stream, never keep fully in memory
* Part II: headers: Last-Modified, ETag and If-None-Match
* Part III: headers: Content-length and Range
* Part IV: Implement `HEAD` operation (efficiently)
* Part V: Throttle download speed
* Part VI: Describe what you send (Content-type, et.al.)

The [sample application](https://github.com/nurkiewicz/download-server) developed throughout these articles is available on GitHub.