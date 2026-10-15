package services;

import models.BindingCredential;
import models.ChannelConfig;
import models.JsonCredential;
import models.McpServer;
import play.db.jpa.JPA;
import utils.CredentialCipher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stored credentials that do not decrypt under this installation's secret (JCLAW-1455), each named with the page
 * where it is entered again. Every reader already treats one as unset; this is what Settings shows so the operator
 * knows which to re-enter, or that the right {@code certs/.env} would bring them all back.
 */
public final class UnreadableCredentials {

    /**
     * @param store where it is kept: {@code config}, a channel ({@code slack}, {@code telegram}, {@code whatsapp}), or {@code mcp}
     * @param owner the config key, the bound agent's name, the MCP server's name, or the channel type
     * @param field which of the owner's credentials it is
     * @param page  the route where the operator enters it again
     */
    public record Item(String store, String owner, String field, String page) {}

    private UnreadableCredentials() {}

    public static List<Item> list() {
        var out = new ArrayList<Item>();
        for (var entry : ConfigService.listAll()) {
            if (entry.unreadable()) {
                out.add(new Item("config", entry.key(), entry.key(), "/settings"));
            }
        }
        var cipher = CredentialCipher.current();
        for (var credential : BindingCredential.values()) {
            var channel = credential.table.replace("_binding", "");
            @SuppressWarnings("unchecked")
            List<Object[]> rows = JPA.em().createNativeQuery("SELECT a.name, b." + credential.column + " FROM "
                    + credential.table + " b JOIN agent a ON a.id = b.agent_id ORDER BY a.name").getResultList();
            for (var row : rows) {
                if (row[1] instanceof String stored && !cipher.open(credential.field(), stored).readable()) {
                    out.add(new Item(channel, String.valueOf(row[0]), credential.column.replace('_', ' '), "/channels/" + channel));
                }
            }
        }
        List<McpServer> servers = McpServer.findAll();
        for (var server : servers) {
            for (var path : JsonCredential.MCP_SERVER.unreadable(server.configJson)) {
                out.add(new Item("mcp", server.name, path, "/mcp-servers"));
            }
        }
        List<ChannelConfig> channels = ChannelConfig.findAll();
        for (var channel : channels) {
            var type = channel.channelType.toLowerCase(Locale.ROOT);
            for (var path : JsonCredential.CHANNEL_CONFIG.unreadable(channel.configJson)) {
                out.add(new Item(type, type + " (app-wide)", path, "/channels/" + type));
            }
        }
        return out;
    }
}
