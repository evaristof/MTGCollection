package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decide qual preço do Scryfall usar para uma carta — e como marcar isso no
 * comentário quando o preço não é o esperado.
 *
 * <p>Algumas cartas foil não têm {@code usd_foil}, só {@code eur_foil}. Em vez
 * de deixar a linha sem preço, usamos o valor em euro <em>sem converter</em> e
 * registramos "{@value #EUR_FOIL_NOTE}" no comentário, para o número na tela
 * nunca ser lido como dólar sem aviso. A marca é gerenciada: some sozinha
 * quando uma sincronização posterior encontra o preço em dólar.</p>
 *
 * <p>O fallback vale só para foil, que é onde o buraco existe no Scryfall —
 * cartas normais sem {@code usd} continuam sem preço.</p>
 */
public final class CardPriceResolver {

    public static final String EUR_FOIL_NOTE = "Preço Foil em EUR";
    public static final String USD = "USD";
    public static final String EUR = "EUR";

    private static final String NOTE_SEPARATOR = " · ";

    /** Casa a marca junto do separador que a antecede, quando houver. */
    private static final Pattern NOTE_PATTERN = Pattern.compile(
            "\\s*·?\\s*" + Pattern.quote(EUR_FOIL_NOTE), Pattern.CASE_INSENSITIVE);

    private CardPriceResolver() {
    }

    /**
     * Preço escolhido e a moeda dele. {@code price} é {@code null} quando o
     * Scryfall não tem preço nenhum para essa variante.
     */
    public record Resolved(BigDecimal price, String currency) {

        /** O preço veio de {@code eur_foil} porque {@code usd_foil} estava vazio. */
        public boolean isEurFoilFallback() {
            return price != null && EUR.equals(currency);
        }
    }

    private static final Resolved NONE = new Resolved(null, USD);

    public static Resolved resolve(ScryfallCard card, boolean foil) {
        return card == null ? NONE : resolve(card.getPrices(), foil);
    }

    public static Resolved resolve(ScryfallPrices prices, boolean foil) {
        if (prices == null) {
            return NONE;
        }
        if (!foil) {
            BigDecimal usd = parse(prices.getUsd());
            return usd == null ? NONE : new Resolved(usd, USD);
        }
        BigDecimal usdFoil = parse(prices.getUsdFoil());
        if (usdFoil != null) {
            return new Resolved(usdFoil, USD);
        }
        BigDecimal eurFoil = parse(prices.getEurFoil());
        return eurFoil == null ? NONE : new Resolved(eurFoil, EUR);
    }

    /**
     * Devolve o comentário com a marca de preço em euro presente ou ausente,
     * conforme o preço usado — preservando o que o usuário escreveu (a marca
     * é sempre anexada no fim, então o flag de conferência manual que o import
     * procura no começo do comentário continua valendo).
     *
     * @return o comentário resultante, ou {@code null} quando fica vazio
     */
    public static String applyEurFoilNote(String comentario, boolean eurFoilPrice) {
        String current = comentario == null ? "" : comentario.trim();
        boolean hasNote = current.toLowerCase(Locale.ROOT)
                .contains(EUR_FOIL_NOTE.toLowerCase(Locale.ROOT));

        if (eurFoilPrice) {
            if (hasNote) {
                return current.isEmpty() ? null : current;
            }
            return current.isEmpty() ? EUR_FOIL_NOTE : current + NOTE_SEPARATOR + EUR_FOIL_NOTE;
        }

        if (!hasNote) {
            return current.isEmpty() ? null : current;
        }
        String cleaned = NOTE_PATTERN.matcher(current).replaceAll("")
                .replaceFirst("^\\s*·\\s*", "")
                .trim();
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
