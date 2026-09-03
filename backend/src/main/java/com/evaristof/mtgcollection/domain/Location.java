package com.evaristof.mtgcollection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

/**
 * A physical storage location for cards in the collection (e.g. "Caixa 3",
 * "Binder Vermelho").
 *
 * <p>This is the managed catalog behind the location autocomplete on the
 * "Cadastro Cartas" screen (and, going forward, "Cartas") and backs the
 * standalone "Cadastro de Localização" CRUD screen. {@link CollectionCard}
 * still stores the location as a free-text column ({@code LOCALIZACAO}) —
 * this table doesn't change that, it just gives the user a managed list of
 * suggestions instead of retyping the same values by hand every time.</p>
 */
@Entity
@Table(name = "LOCATION", uniqueConstraints = @UniqueConstraint(columnNames = {"NAME"}))
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    @Column(name = "DESCRIPTION", length = 1024)
    private String description;

    public Location() {
    }

    public Location(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Location that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
