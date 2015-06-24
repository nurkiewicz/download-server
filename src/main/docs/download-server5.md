# Writing a download server. Part V: Throttle download speed

In the age of botnets that you can rent for few hundred bucks and run your very own distributed-denial-of-service attack, having emergency switches that selectively turn off expensive functionality or degrade performance gratefully is a huge win. Your application is still operational while you mitigate the problem. Of course such safety measure are also valuable under peaks or business hours. One of such mechanisms applying to download servers is throttling download speed dynamically. In order to prevent distributed denial of service attack and excessively high cloud invoices, consider built-in download throttling, that you can enable and fine-tune at runtime. The idea is to limit maximum download speed, either globally or per client (IP? Connection? Cookie? User agent?).

I must admit, I love `java.io` design with lots of simple `Input`/`OutputStream` and `Reader`/`Writer` implementations, each having just one responsibility. You want buffering? GZIPing? Character encoding? File system writing? Just compose desired classes that always work with each other. All right, it's still blocking, but it was designed before reactive hipsters were even born. Nevermind, `java.io` also follows [open-closed principle](https://en.wikipedia.org/wiki/Open/closed_principle): one can simply enhance existing I/O code without touching built-in classes - but by plugging in new decorators. So I created a simple decorator for `InputStream` that slows down reading resource on our side in order to enforce given download speed. I am using [my favorite `RateLimiter` class](http://www.nurkiewicz.com/2012/09/ratelimiter-discovering-google-guava.html):

	public class ThrottlingInputStream extends InputStream {

		private final InputStream target;
		private final RateLimiter maxBytesPerSecond;

		public ThrottlingInputStream(InputStream target, RateLimiter maxBytesPerSecond) {
			this.target = target;
			this.maxBytesPerSecond = maxBytesPerSecond;
		}

		@Override
		public int read() throws IOException {
			maxBytesPerSecond.acquire(1);
			return target.read();
		}

		@Override
		public int read(byte[] b) throws IOException {
			maxBytesPerSecond.acquire(b.length);
			return target.read(b);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			maxBytesPerSecond.acquire(len);
			return target.read(b, off, len);
		}

		//less important below...

		@Override
		public long skip(long n) throws IOException {
			return target.skip(n);
		}

		@Override
		public int available() throws IOException {
			return target.available();
		}

		@Override
		public synchronized void mark(int readlimit) {
			target.mark(readlimit);
		}

		@Override
		public synchronized void reset() throws IOException {
			target.reset();
		}

		@Override
		public boolean markSupported() {
			return target.markSupported();
		}

		@Override
		public void close() throws IOException {
			target.close();
		}
	}

Arbitrary `InputStream` can be wrapped with `ThrottlingInputStream` so that reading is actually slowed down. You can either create new `RateLimiter` per each `ThrottlingInputStream` or one global, shared by all downloads. Of course one might argue that simple `sleep()` (what `RateLimiter` does underneath) wastes a lot of resources, but let's keep this example simple and avoid non-blocking I/O. Now we can easily plug decorator in:

	private InputStreamResource buildResource(FilePointer filePointer) {
		final InputStream inputStream = filePointer.open();
		final RateLimiter throttler = RateLimiter.create(64 * FileUtils.ONE_KB);
		final ThrottlingInputStream throttlingInputStream = new ThrottlingInputStream(inputStream, throttler);
		return new InputStreamResource(throttlingInputStream);
	}

Example above limits download speed to 64 KiB/s - obviously in real life you would want to have such number configurable, preferably at runtime. BTW we already talked about the importance of `Content-Length` header. If you monitor the progress of download with [`pv`](http://linux.die.net/man/1/pv), it will correctly estimate remaining time, which is a nice feature to have:

	~ $ curl localhost:8080/download/4a8883b6-ead6-4b9e-8979-85f9846cab4b | pv > /dev/null
	  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
	                                 Dload  Upload   Total   Spent    Left  Speed
	  0 71.2M    0     0    0     0      0      0 --:--:-- --:--:-- --:--:--     0  16kB 0:00:01 [14,8kB/s]
	  0 71.2M    0 40960    0     0  30097      0  0:41:21  0:00:01  0:41:20 30095  80kB 0:00:02 [  64kB/s]
	  0 71.2M    0  104k    0     0  45110      0  0:27:35  0:00:02  0:27:33 45106 144kB 0:00:03 [  64kB/s]
	  0 71.2M    0  168k    0     0  51192      0  0:24:18  0:00:03  0:24:15 51184 208kB 0:00:04 [  64kB/s]
	  0 71.2M    0  232k    0     0  54475      0  0:22:51  0:00:04  0:22:47 54475 272kB 0:00:05 [63,9kB/s]
	  0 71.2M    0  296k    0     0  56541      0  0:22:00  0:00:05  0:21:55 67476 336kB 0:00:06 [  64kB/s]
	  0 71.2M    0  360k    0     0  57956      0  0:21:28  0:00:06  0:21:22 65536 400kB 0:00:07 [  64kB/s]
	  0 71.2M    0  424k    0     0  58986      0  0:21:06  0:00:07  0:20:59 65536 464kB 0:00:08 [  64kB/s]
	  0 71.2M    0  488k    0     0  59765      0  0:20:49  0:00:08  0:20:41 65536 528kB 0:00:09 [  64kB/s]
	  0 71.2M    0  552k    0     0  60382      0  0:20:36  0:00:09  0:20:27 65536 592kB 0:00:10 [  64kB/s]
	  0 71.2M    0  616k    0     0  60883      0  0:20:26  0:00:10  0:20:16 65536 656kB 0:00:11 [  64kB/s]
	  0 71.2M    0  680k    0     0  61289      0  0:20:18  0:00:11  0:20:07 65536 720kB 0:00:12 [  64kB/s]

As an extra bonus `pv` proved our throttling works (last column). Now without `Content-Length` `pv` is clueless about the actual progress:

	~ $ curl localhost:8080/download/4a8883b6-ead6-4b9e-8979-85f9846cab4b | pv > /dev/null
	  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
	                                 Dload  Upload   Total   Spent    Left  Speed
	100 16384    0 16384    0     0  21116      0 --:--:-- --:--:-- --:--:-- 21113  32kB 0:00:01 [  31kB/s]
	100 81920    0 81920    0     0  46149      0 --:--:--  0:00:01 --:--:-- 46126  96kB 0:00:02 [  64kB/s]
	100  144k    0  144k    0     0  53128      0 --:--:--  0:00:02 --:--:-- 53118 160kB 0:00:03 [  64kB/s]
	100  208k    0  208k    0     0  56411      0 --:--:--  0:00:03 --:--:-- 56406 224kB 0:00:04 [  64kB/s]
	100  272k    0  272k    0     0  58328      0 --:--:--  0:00:04 --:--:-- 58318 288kB 0:00:05 [  64kB/s]
	100  336k    0  336k    0     0  59574      0 --:--:--  0:00:05 --:--:-- 65536 352kB 0:00:06 [  64kB/s]
	100  400k    0  400k    0     0  60450      0 --:--:--  0:00:06 --:--:-- 65536 416kB 0:00:07 [  64kB/s]
	100  464k    0  464k    0     0  61105      0 --:--:--  0:00:07 --:--:-- 65536 480kB 0:00:08 [  64kB/s]
	100  528k    0  528k    0     0  61614      0 --:--:--  0:00:08 --:--:-- 65536 544kB 0:00:09 [  64kB/s]
	100  592k    0  592k    0     0  62014      0 --:--:--  0:00:09 --:--:-- 65536 608kB 0:00:10 [  64kB/s]
	100  656k    0  656k    0     0  62338      0 --:--:--  0:00:10 --:--:-- 65536 672kB 0:00:11 [  64kB/s]
	100  720k    0  720k    0     0  62612      0 --:--:--  0:00:11 --:--:-- 65536 736kB 0:00:12 [  64kB/s]

We see that the data is flowing, but we have no idea how much has left. Thus `Content-Length` is a really important header to have.


---

## Writing a download server

* Part I: Always stream, never keep fully in memory
* Part II: headers: Last-Modified, ETag and If-None-Match
* Part III: headers: Content-length and Range
* Part IV: Implement `HEAD` operation (efficiently)
* Part V: Throttle download speed
* Part VI: Describe what you send (Content-type, et.al.)

The [sample application](https://github.com/nurkiewicz/download-server) developed throughout these articles is available on GitHub.