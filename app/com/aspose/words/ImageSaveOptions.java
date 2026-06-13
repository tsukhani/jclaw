package com.aspose.words;

/**
 * Shim for Aspose's image save options (JCLAW-451). Cobalt's {@code Medias}
 * constructs this with a save-format int ({@code 104} = JPEG in Aspose's
 * {@code SaveFormat}) and hands it to {@link Document#save}. The shim always
 * renders JPEG, so the format hint is accepted and ignored. See {@code package-info}.
 */
public class ImageSaveOptions extends SaveOptions {

    public ImageSaveOptions(int saveFormat) {
        // The format int is Aspose's SaveFormat constant; the PDFBox-backed
        // Document.save always writes JPEG, so nothing to retain.
    }
}
