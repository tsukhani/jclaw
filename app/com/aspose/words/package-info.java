/**
 * COMPATIBILITY SHIM — this is NOT the real Aspose.Words library (JCLAW-451).
 *
 * <p>The WhatsApp-Web (Cobalt) library hard-links a handful of
 * {@code com.aspose.words} classes in its {@code it.auties.whatsapp.util.Medias}
 * helper — the ONLY place in Cobalt that touches Aspose — solely to (a) count a
 * document's pages and (b) render its first page to a JPEG thumbnail for outbound
 * WhatsApp <em>document</em> messages. The real Aspose.Words is a commercial,
 * watermarked artifact that isn't on Maven Central, so JClaw excludes it
 * (see {@code build.gradle.kts}) and provides this minimal shim instead, backed by
 * the Apache PDFBox we already ship.
 *
 * <p>This satisfies Cobalt's static link with no commercial / new dependency, so
 * ALL WhatsApp-Web media outbound works: image/video/audio fully (those paths in
 * {@code Medias} never touch Aspose), PDF documents get a real first-page
 * thumbnail via PDFBox, and other document types get a single-page count plus a
 * blank thumbnail (the message still sends, just without a rich preview).
 *
 * <p><b>Do not extend this package beyond what {@code Medias} actually links
 * against.</b> The only members Cobalt references are:
 * <pre>
 *   Document(InputStream) · int getPageCount() · SaveOutputParameters save(OutputStream, SaveOptions)
 *   ImageSaveOptions(int) extends SaveOptions
 * </pre>
 */
package com.aspose.words;
