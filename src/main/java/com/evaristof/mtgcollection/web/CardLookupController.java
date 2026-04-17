package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.service.CardLookupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cards")
public class CardLookupController {

    private final CardLookupService cardLookupService;

    public CardLookupController(CardLookupService cardLookupService) {
        this.cardLookupService = cardLookupService;
    }

    /**
     * GET /api/cards/by-name?name=...&set=...
     *
     * <p>Returns the full Scryfall card object for the given exact name and
     * set code (including {@code collector_number}, {@code type_line}, and
     * {@code prices}).</p>
     */
    @GetMapping("/by-name")
    public ScryfallCard getCardByName(@RequestParam("name") String name,
                                      @RequestParam("set") String set) {
        return cardLookupService.getCardByNameAndSet(name, set);
    }
}
