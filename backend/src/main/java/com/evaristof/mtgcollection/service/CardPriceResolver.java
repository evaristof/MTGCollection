package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decide qual preço do Scryfall usar para uma carta — e como marcar isso no
 * comentário quando o preço não veio de onde se espera.
 *
 * <p>Carta normal usa {@code usd} e pronto. Foil segue uma cadeia, porque nem
 * toda foil tem cotação em dólar na chave "normal":</p>
 *
 * <ol>
 *   <li>{@code usd_foil} — o caso comum, sem marca nenhuma;</li>
 *   <li>{@code usd_etched} — foil etched, cotada só nessa chave: usa o valor e
 *       marca "{@value #ETCHED_NOTE}";</li>
 *   <li>{@code eur_foil} — sem dólar nenhum: usa o euro <em>sem converter</em>
 *       e marca "{@value #EUR_FOIL_NOTE}", para o número na tela nunca ser
 *       lido como dólar sem aviso.</li>
 * </ol>
 *
 * <p>As marcas são gerenciadas: entram, trocam entre si e somem sozinhas
 * conforme a fonte do preço muda de uma sincronização para outra, sempre
 * preservando o que o usuário escreveu no comentário.</p>
 */
public final class CardPriceResolver {

    public static final String EUR_FOIL_NOTE = "Preço Foil em EUR";
    public static final String ETCHED_NOTE = "Carta Foil Etched";
    public static final String USD = "USD";
    public static final String EUR = "EUR";

    /** Marcas que este resolver controla — qualquer outra é texto do usuário. */
    private static final List<String> MANAGED_NOTES = List.of(ETCHED_NOTE, EUR_FOIL_NOTE);

    private static final String NOTE_SEPARATOR = " · ";

    /** Casa a marca junto do separador que a antecede, quando houver. */
    private static final List<Pattern> MANAGED_NOTE_PATTERNS = MANAGED_NOTES.stream()
            .map(note -> Pattern.compile("\\s*·?\\s*" + Pattern.quote(note), Pattern.CASE_INSENSITIVE))
            .toList();

    private CardPriceResolver() {
    }

    /**
     * Preço escolhido, a moeda dele e a marca que o comentário deve carregar
     * ({@code null} quando o preço veio da fonte esperada). {@code price} é
     * {@code null} quando o Scryfall não tem preço nenhum para essa variante.
     */
    public record Resolved(BigDecimal price, String currency, String note) {

        public boolean hasPrice() {
            return price != null;
        }
    }

    private static final Resolved NONE = new Resolved(null, USD, null);

    public static Resolved resolve(ScryfallCard card, boolean foil) {
        return card == null ? NONE : resolve(card.getPrices(), foil);
    }

    public static Resolved resolve(ScryfallPrices prices, boolean foil) {
        if (prices == null) {
            return NONE;
        }
        if (!foil) {
            BigDecimal usd = parse(prices.getUsd());
            return usd == null ? NONE : new Resolved(usd, USD, null);
        }
        BigDecimal usdFoil = parse(prices.getUsdFoil());
        if (usdFoil != null) {
            return new Resolved(usdFoil, USD, null);
        }
        BigDecimal usdEtched = parse(prices.getUsdEtched());
        if (usdEtched != null) {
            return new Resolved(usdEtched, USD, ETCHED_NOTE);
        }
        BigDecimal eurFoil = parse(prices.getEurFoil());
        return eurFoil == null ? NONE : new Resolved(eurFoil, EUR, EUR_FOIL_NOTE);
    }

    /**
     * Devolve o comentário com a marca do preço resolvido — tirando qualquer
     * outra marca gerenciada que estivesse lá (uma carta que era "em euro" e
     * passou a ter preço etched troca de marca sozinha).
     *
     * <p>O texto do usuário é preservado e a marca vai sempre no fim, então o
     * flag de conferência manual que o import procura no começo do comentário
     * continua valendo.</p>
     *
     * @return o comentário resultante, ou {@code null} quando fica vazio
     */
    public static String applyPriceNote(String comentario, Resolved resolved) {
        String note = resolved == null ? null : resolved.note();
        String cleaned = stripManagedNotes(comentario);
        if (note == null) {
            return cleaned;
        }
        return cleaned == null ? note : cleaned + NOTE_SEPARATOR + note;
    }

    /** O comentário sem nenhuma marca gerenciada; {@code null} se sobrar vazio. */
    private static String stripManagedNotes(String comentario) {
        String current = comentario == null ? "" : comentario.trim();
        if (current.isEmpty()) {
            return null;
        }
        String lower = current.toLowerCase(Locale.ROOT);
        boolean hasAnyNote = MANAGED_NOTES.stream()
                .anyMatch(note -> lower.contains(note.toLowerCase(Locale.ROOT)));
        if (!hasAnyNote) {
            return current;
        }
        String cleaned = current;
        for (Pattern pattern : MANAGED_NOTE_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("");
        }
        cleaned = cleaned.replaceFirst("^\\s*·\\s*", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
