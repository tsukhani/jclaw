package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * A saved, reusable prompt in the operator's Prompts Library (JCLAW-813).
 *
 * <p>Operator-level — not scoped per agent or per user (single-operator
 * Personal Edition, same convention as {@link McpServer}). A prompt is one
 * title + free-form prompt text, filed under exactly one fixed {@link Category}
 * (the controlled, scannable axis) and tagged with an optional free-form,
 * comma-separated {@code tags} string (the unlimited, user-defined axis). The
 * two axes are deliberately different: categories are a closed taxonomy for
 * consistent grouping/filtering, tags are a folksonomy for personal
 * cross-cutting labels — so there is intentionally no "custom category" table.
 *
 * <p>Distinct from {@code agents.SystemPromptAssembler}'s notion of a "prompt"
 * (an agent's assembled system prompt); this is the user-facing prompt library,
 * unrelated code.
 */
@Entity
@Table(name = "prompt", indexes = {
        @Index(name = "idx_prompt_category", columnList = "category"),
        @Index(name = "idx_prompt_updated_at", columnList = "updated_at")
})
public class Prompt extends Model {

    /**
     * The fixed set of prompt categories. A controlled taxonomy (not
     * operator-editable) — the human label lives here; the wire value
     * ({@link #name()}) is the stable key the frontend maps to a Heroicon
     * (mirroring the Tools page's icon style), so a label rewording never
     * shifts stored data or the rendered glyph. Free-form organization is the
     * job of {@link #tags}.
     */
    public enum Category {
        CODING("Coding"),
        WRITING("Writing"),
        ANALYSIS("Analysis"),
        CREATIVE("Creative"),
        BUSINESS("Business"),
        CUSTOM("Custom");

        public final String label;

        Category(String label) {
            this.label = label;
        }

        /** Resolve a wire value (enum name, case-insensitive) to a category, or
         *  {@code null} when it matches none — callers decide whether that's a
         *  400 (create/update) or a coerce-to-{@link #CUSTOM} (lenient import). */
        public static Category fromValue(String raw) {
            if (raw == null) return null;
            for (var c : values()) {
                if (c.name().equalsIgnoreCase(raw.trim())) return c;
            }
            return null;
        }
    }

    @Column(nullable = false, length = 200)
    public String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    /** Free-form, comma-separated tags, stored verbatim (nullable). Split/trim
     *  happens only at render/search time — no normalization on write, mirroring
     *  {@code Task.scheduleDisplay}'s "preserve the operator's raw input" rule. */
    @Column(length = 500)
    public String tags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Category category;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Newest-edited first — the order the library page renders. */
    public static List<Prompt> findAllOrdered() {
        return Prompt.find("ORDER BY updatedAt DESC").fetch();
    }
}
