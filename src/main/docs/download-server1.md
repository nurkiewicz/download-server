# Writing a download server. Part I: Always stream, never keep fully in memory

Downloading various files (either text or binary) is a bread and butter of every enterprise application. PDF documents, attachments, media, executables, CSV, very large files, etc. Almost every application, sooner or later, will have to provide some form of download. Downloading is implemented in terms of HTTP, so it's important to fully embrace this protocol and take full advantage of it. Especially in Internet facing applications features like caching or user experience are worth considering. This series of articles provides a list of aspects that you might want to consider when implementing all sorts of download servers. Note that I avoid "*best practices*" term, these are just guidelines that I find useful but are not necessarily always applicable.

One of the biggest scalability issues is loading whole file into memory before streaming it. Loading full file into `byte[]` to later return it e.g. from Spring MVC controller is unpredictable and doesn't scale. The amount of memory your server will consume depends linearly on number of concurrent connections *times* average file size - factors you don't really want to depend on so much. It's extremely easy to stream contents of a file directly from your server to the client byte-by-byte (with buffering), there are actually many techniques to achieve that. The easiest one is to copy bytes manually:

	@RequestMapping(method = GET)
	public void download(OutputStream output) throws IOException {
		try(final InputStream myFile = openFile()) {
			IOUtils.copy(myFile, output);
		}
	}

Your `InputStream` doesn't even have to be buffered, [`IOUtils.copy()`](https://commons.apache.org/proper/commons-io/apidocs/org/apache/commons/io/IOUtils.html#copy(java.io.InputStream,%20java.io.OutputStream)) will take care of that. However this implementation is rather low-level and hard to unit test. Instead I suggest returning [`Resource`](http://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/core/io/Resource.html):

	@RestController
	@RequestMapping("/download")
	public class DownloadController {

		private final FileStorage storage;

		@Autowired
		public DownloadController(FileStorage storage) {
			this.storage = storage;
		}

		@RequestMapping(method = GET, value = "/{uuid}")
		public Resource download(@PathVariable UUID uuid) {
			return storage
					.findFile(uuid)
					.map(this::prepareResponse)
					.orElseGet(this::notFound);
		}

		private Resource prepareResponse(FilePointer filePointer) {
			final InputStream inputStream = filePointer.open();
			return new InputStreamResource(inputStream);
		}

		private Resource notFound() {
			throw new NotFoundException();
		}
	}

	@ResponseStatus(value= HttpStatus.NOT_FOUND)
	public class NotFoundException extends RuntimeException {
	}

Two abstractions were created to decouple Spring controller from file storage mechanism. `FilePointer` is a file descriptor, irrespective to where that file was taken. Currently we use one method from it:

	public interface FilePointer {

		InputStream open();

		//more to come

	}

`open()` allows reading the actual file, no matter where it comes from (file system, database BLOB, Amazon S3, etc.) We will gradually extend `FilePointer` to support more advanced features, like file size and MIME type. The process of finding and creating `FilePointer`s is governed by `FileStorage` abstraction:

	public interface FileStorage {
		Optional<FilePointer> findFile(UUID uuid);
	}

Streaming allows us to handle hundreds of concurrent requests without significant impact on memory and GC (only a small buffer is allocated in `IOUtils`). BTW I am using `UUID` to identify files rather than names or other form of sequence number. This makes it harder to guess individual resource names, thus more secure (obscure). More on that in next articles. Having this basic setup we can reliably serve lots of concurrent connections with minimal impact on memory. Remember that many components in Spring framework and other libraries (e.g. servlet filters) may buffer full response before returning it. Therefore it's really important to have an integration test trying to download huge file (in tens of GiB) and making sure the application doesn't crash.

---

## Writing a download server

* Part I: Always stream, never keep fully in memory
* Part II: headers: Last-Modified, ETag and If-None-Match
* Part III: headers: Content-length and Range
* Part IV: Implement `HEAD` operation (efficiently)
* Part V: Throttle download speed
* Part VI: Describe what you send (Content-type, et.al.)

The [sample application](https://github.com/nurkiewicz/download-server) developed throughout these articles is available on GitHub.