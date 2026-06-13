package controllers;

import channels.WhatsAppCobaltRunner;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import play.mvc.Controller;
import play.mvc.With;

import static utils.GsonHolder.INSTANCE;

/**
 * Read-only QR-pairing status endpoint for WhatsApp-Web (Cobalt) bindings
 * (JCLAW-448). Its own controller, separate from
 * {@link ApiWhatsAppBindingsController} (which the Cloud-API track owns), so the
 * two tracks never touch the same file.
 *
 * <p>The {@link channels.WhatsAppCobaltRunner} populates a process-global map of
 * pending QR strings as Cobalt emits them during pairing; this endpoint surfaces
 * the latest one plus a paired/unpaired flag so the pairing UI can poll
 * (render the QR, then flip to "connected" once the user scans it). It never
 * mutates session state — pairing is driven entirely by the runner.
 */
@With(AuthCheck.class)
public class WhatsAppQrController extends Controller {

    /** JSON shape the pairing UI polls. {@code qr} is the latest pending QR
     *  string (null once paired or before the first QR is emitted); {@code paired}
     *  is true once the session is connected with an owner JID. */
    private record QrStatus(Long bindingId, String transport, boolean paired, String qr) {}

    /**
     * GET the pairing status for one binding. 404 when the binding doesn't exist;
     * a {@code paired:false, qr:null} response for a Cloud-API binding (which never
     * pairs via QR) so the UI can distinguish "wrong transport" cheaply.
     */
    public static void status(Long id) {
        WhatsAppBinding binding = WhatsAppBinding.findById(id);
        notFoundIfNull(binding);

        boolean isWeb = binding.transport == WhatsAppTransport.WHATSAPP_WEB;
        boolean paired = isWeb && WhatsAppCobaltRunner.isPaired(id);
        // Suppress the QR once paired — a stale QR after pairing would be confusing.
        String qr = isWeb && !paired ? WhatsAppCobaltRunner.pendingQr(id) : null;

        var status = new QrStatus(
                binding.id,
                (binding.transport != null ? binding.transport : WhatsAppTransport.CLOUD_API).name(),
                paired,
                qr);
        renderJSON(INSTANCE.toJson(status));
    }
}
