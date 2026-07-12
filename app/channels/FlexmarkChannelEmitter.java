package channels;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Shared flexmark AST walker for the channel markdown formatters (JCLAW-720):
 * {@link TelegramMarkdownFormatter} (Markdown → Telegram-safe HTML) and
 * {@link SlackMarkdownFormatter} (Markdown → Slack mrkdwn). Both previously carried
 * their own copy of the same recursive descent — iterate a node's children,
 * dispatch each on its flexmark type — differing only in the per-node syntax they
 * emit.
 *
 * <p>This type owns the walk exactly once: {@link #emitChildren} recurses and
 * {@link #emit} dispatches on node type to the abstract emit hooks a subclass
 * implements, all appending to the shared {@link #out} buffer. A subclass supplies
 * only its syntax mapping — an HTML {@code <b>…</b>} vs a mrkdwn {@code *…*}, etc.
 * Node types only one channel's markdown extensions produce (Telegram's typographic
 * and raw-HTML nodes, Slack's raw-HTML nodes) are caught by overriding
 * {@link #emitFallback}.
 *
 * <p>Follows the house Pure-Fabrication idiom ({@link IdleDebounceBuffer},
 * {@code TelegramOutboundPlanner}): the shared machinery lives here once; a third
 * channel would implement only the syntax hooks.
 */
abstract class FlexmarkChannelEmitter {

    /** The rendered output, appended to by the subclass's emit hooks. */
    protected final StringBuilder out = new StringBuilder();

    /**
     * Walk {@code doc}'s children and return the emitted string with trailing
     * whitespace stripped — the shape both formatters return from their static entry
     * points.
     */
    protected String render(Node doc) {
        emitChildren(doc);
        return out.toString().stripTrailing();
    }

    /** Emit every child of {@code parent} in document order. */
    protected void emitChildren(Node parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            emit(child);
        }
    }

    /**
     * Dispatch one node to its emit hook. This single switch is the AST walk shared
     * by every channel; the {@code default} arm delegates to {@link #emitFallback} so
     * a subclass can catch the extra node types its own markdown extensions produce
     * before falling back to walking children.
     */
    protected void emit(Node node) {
        switch (node) {
            case Heading n -> emitHeading(n);
            case Paragraph n -> emitParagraph(n);
            case StrongEmphasis n -> emitStrongEmphasis(n);
            case Emphasis n -> emitEmphasis(n);
            case Strikethrough n -> emitStrikethrough(n);
            case Code n -> emitCode(n);
            case FencedCodeBlock n -> emitFencedCodeBlock(n);
            case IndentedCodeBlock n -> emitIndentedCodeBlock(n);
            case BlockQuote n -> emitBlockQuote(n);
            case BulletList n -> emitBulletList(n);
            case OrderedList n -> emitOrderedList(n);
            case TableBlock n -> emitTable(n);
            case Link n -> emitLink(n);
            case AutoLink n -> emitAutoLink(n);
            case Text n -> emitText(n);
            case SoftLineBreak _ -> emitSoftLineBreak();
            case HardLineBreak _ -> emitHardLineBreak();
            case ThematicBreak _ -> emitThematicBreak();
            default -> emitFallback(node);
        }
    }

    /**
     * Default for unenumerated node types: walk children. Subclasses override to
     * catch the extra nodes their markdown extensions emit (typographic decorations,
     * raw HTML) before falling back to {@code super.emitFallback}.
     */
    protected void emitFallback(Node node) {
        emitChildren(node);
    }

    // ── Per-node syntax hooks (implemented per channel) ──

    protected abstract void emitHeading(Heading node);

    protected abstract void emitParagraph(Paragraph node);

    protected abstract void emitStrongEmphasis(StrongEmphasis node);

    protected abstract void emitEmphasis(Emphasis node);

    protected abstract void emitStrikethrough(Strikethrough node);

    protected abstract void emitCode(Code node);

    protected abstract void emitFencedCodeBlock(FencedCodeBlock node);

    protected abstract void emitIndentedCodeBlock(IndentedCodeBlock node);

    protected abstract void emitBlockQuote(BlockQuote node);

    protected abstract void emitBulletList(BulletList node);

    protected abstract void emitOrderedList(OrderedList node);

    protected abstract void emitTable(TableBlock node);

    protected abstract void emitLink(Link node);

    protected abstract void emitAutoLink(AutoLink node);

    protected abstract void emitText(Text node);

    protected abstract void emitSoftLineBreak();

    protected abstract void emitHardLineBreak();

    protected abstract void emitThematicBreak();

    // ── Shared helpers ──

    /** Remove trailing newlines from {@link #out} (used when a list item's paragraph
     *  emit leaves gratuitous blank lines before the item terminator). */
    protected void trimTrailingNewlines() {
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
    }

    /** First child of {@code parent} assignable to {@code type}, or {@code null}. */
    protected static <T extends Node> T findChild(Node parent, Class<T> type) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (type.isInstance(child)) return type.cast(child);
        }
        return null;
    }
}
