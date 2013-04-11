package org.weakref.s3fs;

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.transform;
import static java.lang.String.format;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

public class S3Path implements Path {
	public static final String PATH_SEPARATOR = "/";

	private final String bucket;

	private final List<String> parts;
	private S3FileSystem fileSystem;

	/**
	 * path must be a string of the form "/{bucket}", "/{bucket}/{key}" or just
	 * "{key}"
	 */
	public S3Path(S3FileSystem fileSystem, String path) {
		List<String> parts = ImmutableList.copyOf(Splitter.on(PATH_SEPARATOR)
				.omitEmptyStrings().split(path));

		String bucket = null;
		List<String> pathParts = parts;

		if (path.startsWith(PATH_SEPARATOR)) { // absolute path
			Preconditions.checkArgument(parts.size() >= 1,
					"path must start with bucket name");

			bucket = parts.get(0);

			if (!parts.isEmpty()) {
				pathParts = parts.subList(1, parts.size());
			}
		}

		if (bucket != null) {
			bucket = bucket.replace("/", "");
		}

		this.bucket = bucket;
		this.parts = ImmutableList.copyOf(transform(pathParts, strip("/")));
		this.fileSystem = fileSystem;
	}

	/**
	 * Build an S3Path from path segments. '/' are stripped from each segment.
	 */
	public S3Path(S3FileSystem fileSystem, String bucket, String... parts) {
		this(fileSystem, bucket, ImmutableList.copyOf(parts));
	}

	private S3Path(S3FileSystem fileSystem, String bucket,
			Iterable<String> parts) {
		if (bucket != null) {
			bucket = bucket.replace("/", "");
		}

		this.bucket = bucket;

		this.parts = ImmutableList.copyOf(transform(parts, strip("/")));
		this.fileSystem = fileSystem;
	}

	/**
	 * path must be a string of the form "/{bucket}", "/{bucket}/{key}" or just
	 * "{key}"
	 * 
	 * redundant '/' are stripped
	 */
	public static S3Path forPath(String path) {
		return new S3Path((S3FileSystem) FileSystems.getFileSystem(URI
				.create("s3:///")), path);
	}

	public String getBucket() {
		return bucket;
	}
	/**
	 * key for amazon without final slash.
	 * <b>note:</b> the final slash need to be added to save a directory (Amazon s3 spec)
	 */
	public String getKey() {
		if (parts.isEmpty()) {
			return "";
		}

		ImmutableList.Builder<String> builder = ImmutableList
				.<String> builder().addAll(parts);

		return Joiner.on(PATH_SEPARATOR).join(builder.build());
	}

	@Override
	public S3FileSystem getFileSystem() {
		return this.fileSystem;
	}

	@Override
	public boolean isAbsolute() {
		return bucket != null;
	}

	@Override
	public Path getRoot() {
		if (isAbsolute()) {
			return new S3Path(fileSystem, bucket, ImmutableList.<String> of());
		}

		return null;
	}

	@Override
	public Path getFileName() {
		if (!parts.isEmpty()) {
			return new S3Path(fileSystem, null, parts.subList(parts.size() - 1,
					parts.size()));
		}

		return null;
	}

	@Override
	public Path getParent() {
		// bucket name no forma parte de los parts
		if (parts.isEmpty()) {
			return null;
		}
		// si es null o vacio y solo tengo un path relativo
		// no tengo parent
		if (parts.size() == 1 && (bucket == null || bucket.isEmpty())){
			return null;
		}

		return new S3Path(fileSystem, bucket,
				parts.subList(0, parts.size() - 1));
	}

	@Override
	public int getNameCount() {
		return parts.size();
	}

	@Override
	public Path getName(int index) {
		return new S3Path(fileSystem, null, parts.subList(index, index + 1));
	}

	@Override
	public Path subpath(int beginIndex, int endIndex) {
		return new S3Path(fileSystem, null, parts.subList(beginIndex, endIndex));
	}

	@Override
	public boolean startsWith(Path other) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean startsWith(String other) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean endsWith(Path other) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean endsWith(String other) {
		return this.endsWith(new S3Path(this.fileSystem, other));
	}

	@Override
	public Path normalize() {
		return this;
	}

	@Override
	public Path resolve(Path other) {
		Preconditions.checkArgument(other instanceof S3Path,
				"other must be an instance of %s", S3Path.class.getName());

		S3Path s3Path = (S3Path) other;

		if (s3Path.isAbsolute()) {
			return s3Path;
		}

		if (s3Path.parts.isEmpty()) { // other is relative and empty
			return this;
		}

		return new S3Path(fileSystem, bucket, concat(parts, s3Path.parts));
	}

	@Override
	public Path resolve(String other) {
		return resolve(forPath(other));
	}

	@Override
	public Path resolveSibling(Path other) {
		Preconditions.checkArgument(other instanceof S3Path,
				"other must be an instance of %s", S3Path.class.getName());

		S3Path s3Path = (S3Path) other;

		Path parent = getParent();

		if (parent == null || s3Path.isAbsolute()) {
			return s3Path;
		}

		if (s3Path.parts.isEmpty()) { // other is relative and empty
			return parent;
		}

		return new S3Path(fileSystem, bucket, concat(
				parts.subList(0, parts.size() - 1), s3Path.parts));
	}

	@Override
	public Path resolveSibling(String other) {
		return resolveSibling(forPath(other));
	}

	@Override
	public Path relativize(Path other) {
		Preconditions.checkArgument(other instanceof S3Path,
				"other must be an instance of %s", S3Path.class.getName());
		S3Path s3Path = (S3Path) other;

		if (this.equals(other)) {
			return S3Path.forPath("");
		}

		Preconditions.checkArgument(isAbsolute(),
				"Path is already relative: %s", this);
		Preconditions.checkArgument(s3Path.isAbsolute(),
				"Cannot relativize against a relative path: %s", s3Path);
		Preconditions.checkArgument(bucket.equals(s3Path.getBucket()),
				"Cannot relativize paths with different buckets: '%s', '%s'",
				this, other);

		// TODO
		throw new UnsupportedOperationException();
	}

	@Override
	public URI toUri() {
		return URI.create("s3://" + bucket
				+ Joiner.on(PATH_SEPARATOR).join(parts));
	}

	@Override
	public Path toAbsolutePath() {
		if (isAbsolute()) {
			return this;
		}

		throw new IllegalStateException(format(
				"Relative path cannot be made absolute: %s", this));
	}

	@Override
	public Path toRealPath(LinkOption... options) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public File toFile() {
		throw new UnsupportedOperationException();
	}

	@Override
	public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
			WatchEvent.Modifier... modifiers) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events)
			throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Iterator<Path> iterator() {
		ImmutableList.Builder<Path> builder = ImmutableList.builder();

		for (Iterator<String> iterator = parts.iterator(); iterator.hasNext();) {
			String part = iterator.next();

			boolean isDirectory = iterator.hasNext();

			builder.add(new S3Path(fileSystem, null, ImmutableList.of(part)));
		}

		return builder.build().iterator();
	}

	@Override
	public int compareTo(Path other) {
		return toString().compareTo(other.toString());
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();

		if (isAbsolute()) {
			builder.append(PATH_SEPARATOR);
			builder.append(bucket);
			builder.append(PATH_SEPARATOR);
		}

		builder.append(Joiner.on(PATH_SEPARATOR).join(parts));

		return builder.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		S3Path paths = (S3Path) o;

		if (bucket != null ? !bucket.equals(paths.bucket)
				: paths.bucket != null) {
			return false;
		}
		if (!parts.equals(paths.parts)) {
			return false;
		}

		return true;
	}

	@Override
	public int hashCode() {
		int result = bucket != null ? bucket.hashCode() : 0;
		result = 31 * result + parts.hashCode();
		return result;
	}

	private static Function<String, String> strip(final String str) {
		return new Function<String, String>() {
			public String apply(String input) {
				return input.replace(str, "");
			}
		};
	}
}