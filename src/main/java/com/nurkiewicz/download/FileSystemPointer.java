package com.nurkiewicz.download;

import com.google.common.base.MoreObjects;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.common.net.MediaType;

import java.io.*;
import java.time.Instant;
import java.util.Optional;

public class FileSystemPointer implements FilePointer {

	private final File target;
	private final HashCode tag;
	private final MediaType mediaTypeOrNull;

	public FileSystemPointer(File target) {
		try {
			this.target = target;
			this.tag = Files.hash(target, Hashing.sha512());
			final String contentType = java.nio.file.Files.probeContentType(target.toPath());
			this.mediaTypeOrNull = contentType != null ?
					MediaType.parse(contentType) :
					null;
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
	public long getSize() {
		return target.length();
	}

	@Override
	public String getOriginalName() {
		return target.getName();
	}

	@Override
	public String getEtag() {
		return "\"" + tag + "\"";
	}

	@Override
	public Optional<MediaType> getMediaType() {
		return Optional.ofNullable(mediaTypeOrNull);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("target", target)
				.add("originalName", getOriginalName())
				.add("size", getSize())
				.add("tag", tag)
				.add("mediaType", mediaTypeOrNull)
				.toString();
	}

	@Override
	public boolean matchesEtag(String requestEtag) {
		return getEtag().equals(requestEtag);
	}

	@Override
	public Instant getLastModified() {
		return Instant.ofEpochMilli(target.lastModified());
	}

	@Override
	public boolean modifiedAfter(Instant clientTime) {
		return !clientTime.isBefore(getLastModified());
	}
}
