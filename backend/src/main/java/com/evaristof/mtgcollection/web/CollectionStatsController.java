package com.evaristof.mtgcollection.web;

import com.evaristof.mtgcollection.service.CollectionStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agregados da coleção atual para a tela Gráficos. Os números históricos
 * (por snapshot) ficam em {@code /api/collection/datadumps/stats/...}.
 */
@RestController
@RequestMapping("/api/collection/stats")
public class CollectionStatsController {

    private final CollectionStatsService service;

    public CollectionStatsController(CollectionStatsService service) {
        this.service = service;
    }

    /** Valor atual (preço × quantidade) somado por localização. */
    @GetMapping("/value-by-location")
    public List<CollectionStatsService.LocationValue> valueByLocation() {
        return service.valueByLocation();
    }
}
