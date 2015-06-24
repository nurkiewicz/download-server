Writing a download server. Part III: headers: Content-length and Range

We will explore more HTTP request and response headers this time to improve download server implementation: `Content-length` and `Range`. The former signals how big the download is, the latter allows downloading files partially or continue after failure from where we started.

# `Content-length` response header

`Content-length` response header is tremendously helpful for clients that track download progress. If you send expected resource size in advance before even starting to stream bytes, client like web browser can show very accurate progress bar and even estimate total download time by measuring average download speed. Without `Content-length` client will just keep downloading as long as possible, hoping the stream will end one day. There are however some circumstances when obtaining precise content length is hard. For example maybe you stream resources from some other download server or your resource is compressed on the fly and sent directly to servlet response. In both of these cases the best you can do is actually caching the data locally on disk, figuring out what the size is and start streaming when data is available. This is not a contradiction to an advice to always stream, never keep fully in memory. In this case we store temporary file on disk, but still stream it once fully ready and its size is known.

From Java perspective providing content length is darn simple:

	private ResponseEntity<Resource> response(FilePointer filePointer, HttpStatus status, Resource body) {
		return ResponseEntity
				.status(status)
				.eTag(filePointer.getEtag())
				.contentLength(filePointer.getSize())
				.lastModified(filePointer.getLastModified().toEpochMilli())
				.body(body);
	}

Notice that a method [`Resource.contentLength()`](http://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/core/io/Resource.html#contentLength--) also exists, but different types of resource compute it differently, sometimes eagerly reading the whole resource. I have my own `FilePointer` abstraction that knows the size of file we want to download.

# 7. Implement `Range` request header

`Range` header is a "new" feature of HTTP/1.1 described nicely in [RFC 7233](https://tools.ietf.org/html/rfc7233). The idea is that client can request just part of the resource (in terms of byte range) mainly for two reasons:

* Previous download was interrupted and we don't want to repeat the same work. In this case client knows how many bytes it received and asks for the remaining part
* We are streaming data (e.g. video) and we want to skip certain part. Think about online player like Youtube and clicking in the middle of progress bar. Client can simply estimate which part of the file it needs now, proportionally to movie duration.

Not all servers need to implement `Range` requests so there is a bit of negotiation happening. First client sends a request asking for just part of the file, first 100 bytes in this example:

	> GET /big_buck_bunny_1080p_surround.avi HTTP/1.1
	> Range: bytes=0-99
	...

If the target server supports range request, it responds with `206 Partial Content`:

	< HTTP/1.1 206 Partial Content
	< Last-Modified: Tue, 06 May 2008 11:21:35 GMT
	< ETag: "8000089-375a6422-44c8e0d0f0dc0"
	< Accept-Ranges: bytes
	< Content-Length: 100
	< Content-Range: bytes 0-99/928670754

There are many interesting headers here. First of all it's 206, not 200 OK as usual. If it was 200 OK, client must assume that server doesn't support range requests. The sample server is very well behaving, it also sends us `Last-Modified` and `ETag` headers to improve caching. Additionally the server confirms it's capable of handling `Range` requests by sending `Accept-Ranges` header. Currently only `bytes` is widely used, but RFC permits other range units in the future (seconds? frames?) Last two headers are the most interesting. `Content-Length` no longer declares the total resource size - it's the size of range(s) we requested, 100 bytes in this case. The size of full resource is encoded in `Content-Range`: `bytes 0-99/928670754`. The server is very precise in terms of what we received: first 100 bytes (`0-99`) while the total resource size is `928670754`. Knowing the total size client can basically request parts of the file in multiple chunks.

The specification of `Range` requests allows a lot of flexibility, for example we can ask for multiple ranges in one request, e.g.: 

	> GET /big_buck_bunny_1080p_surround.avi HTTP/1.1
	> Range: bytes=0-9,1000-1009
	...
	< HTTP/1.1 206 Partial Content
	< Accept-Ranges: bytes
	< Content-Type: multipart/byteranges; boundary=5187ab27335732
	< 

	--5187ab27335732
	Content-type: video/x-msvideo
	Content-range: bytes 0-9/928670754

	[data]
	--5187ab27335732
	Content-type: video/x-msvideo
	Content-range: bytes 1000-1009/928670754

	[data]
	--5187ab27335732--

However the server is free to optimize multiple range requests, like rearranging them, merging, etc. Implementing partial requests from scratch is way beyond the scope of this article and I hope you don't have to do it yourself. For example Spring starting from 4.2.x has comprehensive, built-in support for partial requests of static resources, see: [`ResourceHttpRequestHandler` line 463](https://github.com/spring-projects/spring-framework/blob/v4.2.0.RC1/spring-webmvc/src/main/java/org/springframework/web/servlet/resource/ResourceHttpRequestHandler.java#L463).


---

## Writing a download server

* Part I: Always stream, never keep fully in memory
* Part II: headers: Last-Modified, ETag and If-None-Match
* Part III: headers: Content-length and Range
* Part IV: Implement `HEAD` operation (efficiently)
* Part V: Throttle download speed
* Part VI: Describe what you send (Content-type, et.al.)

The [sample application](https://github.com/nurkiewicz/download-server) developed throughout these articles is available on GitHub.