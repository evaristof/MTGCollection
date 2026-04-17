package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CardPriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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
        BigDecimal price = cardPriceService.getPriceByNameAndSet(cardName, setCode, foil);
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
        BigDecimal price = cardPriceService.getPriceBySetAndNumber(setCode, cardNumber, foil);
        return ResponseEntity.ok(buildResponse(null, setCode, cardNumber, foil, price));
    }

    private Map<String, Object> buildResponse(String name, String set, String number, boolean foil, BigDecimal price) {
        Map<String, Object> body = new HashMap<>();
        if (name != null) body.put("name", name);
        body.put("set", set);
        if (number != null) body.put("collectorNumber", number);
        body.put("foil", foil);
        body.put("currency", "USD");
        body.put("price", price);
        return body;
    }
}
