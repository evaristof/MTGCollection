package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CardPriceResolver;
import com.evaristof.mtgcollection.service.CardPriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/prices")
public class CardPriceController {

    private final CardPriceService cardPriceService;

    public CardPriceController(CardPriceService cardPriceService) {
        this.cardPriceService = cardPriceService;
    }

    /**
     * GET /api/prices/by-name?name=...&set=...&foil=true|false
     */
    @GetMapping("/by-name")
    public ResponseEntity<Map<String, Object>> getByName(
            @RequestParam("name") String cardName,
            @RequestParam("set") String setCode,
            @RequestParam(value = "foil", defaultValue = "false") boolean foil) {
        CardPriceResolver.Resolved price = cardPriceService.getPriceByNameAndSet(cardName, setCode, foil);
        return ResponseEntity.ok(buildResponse(cardName, setCode, null, foil, price));
    }

    /**
     * GET /api/prices/by-number?set=...&number=...&foil=true|false
     */
    @GetMapping("/by-number")
    public ResponseEntity<Map<String, Object>> getByNumber(
            @RequestParam("set") String setCode,
            @RequestParam("number") String cardNumber,
            @RequestParam(value = "foil", defaultValue = "false") boolean foil) {
        CardPriceResolver.Resolved price = cardPriceService.getPriceBySetAndNumber(setCode, cardNumber, foil);
        return ResponseEntity.ok(buildResponse(null, setCode, cardNumber, foil, price));
    }

    private Map<String, Object> buildResponse(String name, String set, String number, boolean foil,
                                              CardPriceResolver.Resolved resolved) {
        Map<String, Object> body = new HashMap<>();
        if (name != null) body.put("name", name);
        body.put("set", set);
        if (number != null) body.put("collector_number", number);
        body.put("foil", foil);
        // Foil sem usd_foil cai em usd_etched ou eur_foil — por isso a moeda é
        // parte da resposta (e não uma constante) e a origem vai junto em
        // "note", que é o mesmo texto gravado no comentário da carta.
        body.put("currency", resolved.currency());
        body.put("price", resolved.price());
        body.put("note", resolved.note());
        return body;
    }
}
