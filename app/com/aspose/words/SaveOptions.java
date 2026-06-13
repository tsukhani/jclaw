package com.aspose.words;

/**
 * Shim base type for Aspose save options (JCLAW-451). Cobalt's {@code Medias}
 * references this only as the declared parameter type of
 * {@link Document#save(java.io.OutputStream, SaveOptions)}; {@link ImageSaveOptions}
 * is the concrete value it passes. Empty by design — see {@code package-info}.
 */
public class SaveOptions {
}
