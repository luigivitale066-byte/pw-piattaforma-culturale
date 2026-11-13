package it.tesi.cultural.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final JdbcTemplate jdbcTemplate;

    public CatalogController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    List<CatalogItem> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
                // Il limite massimo impedisce richieste che carichino pagine sproporzionate.
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "page must be non-negative and size must be between 1 and 100");
        }
        String search = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        String selectedCategory = category == null || category.isBlank() ? null : category.trim();
        return jdbcTemplate.query("""
                SELECT id, title, description, author, year, media_url, category, created_at
                FROM catalog_items
                WHERE (CAST(? AS text) IS NULL OR lower(title) LIKE ? OR lower(coalesce(description, '')) LIKE ?
                       OR lower(coalesce(author, '')) LIKE ?)
                  AND (CAST(? AS text) IS NULL OR category = ?)
                ORDER BY title, id
                LIMIT ? OFFSET ?
                """, ITEM_ROW_MAPPER, search, search, search, search, selectedCategory,
                selectedCategory, size, (long) page * size);
    }

    @GetMapping("/{id}")
    CatalogItem get(@PathVariable Integer id) {
        List<CatalogItem> items = jdbcTemplate.query("""
                SELECT id, title, description, author, year, media_url, category, created_at
                FROM catalog_items WHERE id = ?
                """, ITEM_ROW_MAPPER, id);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog item not found");
        }
        return items.get(0);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_OPERATOR')")
    CatalogItem create(@Valid @RequestBody CatalogItemRequest request) {
                // I collegamenti esterni sono limitati ai protocolli navigabili dalla pagina.
        if (request.mediaUrl() != null && !"https".equalsIgnoreCase(request.mediaUrl().getScheme())
                && !"http".equalsIgnoreCase(request.mediaUrl().getScheme())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaUrl must use HTTP or HTTPS");
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO catalog_items (title, description, author, year, media_url, category)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, title, description, author, year, media_url, category, created_at
                """, ITEM_ROW_MAPPER, request.title(), request.description(), request.author(),
                request.year(), request.mediaUrl() == null ? null : request.mediaUrl().toString(),
                request.category());
    }

    public record CatalogItem(
            Integer id, String title, String description, String author, Integer year,
            URI mediaUrl, String category, OffsetDateTime createdAt) {}

    public record CatalogItemRequest(
            @NotBlank @Size(max = 512) String title,
            String description,
            @Size(max = 255) String author,
            Integer year,
            URI mediaUrl,
            @Size(max = 128) String category) {}

    private static final RowMapper<CatalogItem> ITEM_ROW_MAPPER = (resultSet, rowNum) ->
            new CatalogItem(
                    resultSet.getObject("id", Integer.class),
                    resultSet.getString("title"),
                    resultSet.getString("description"),
                    resultSet.getString("author"),
                    (Integer) resultSet.getObject("year"),
                    resultSet.getString("media_url") == null
                            ? null : URI.create(resultSet.getString("media_url")),
                    resultSet.getString("category"),
                    resultSet.getObject("created_at", OffsetDateTime.class));
}
