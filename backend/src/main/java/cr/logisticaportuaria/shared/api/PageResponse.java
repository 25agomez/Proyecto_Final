package cr.logisticaportuaria.shared.api;

import java.util.List;

/**
 * Page of results in the shape used by the OpenAPI contract
 * ({@code content}, {@code page}, {@code size}, {@code totalElements}, {@code totalPages}).
 *
 * @param content       items of the page
 * @param page          zero based page number
 * @param size          page size
 * @param totalElements total number of matching rows
 * @param totalPages    total number of pages
 * @param <T>           type of the items
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /**
     * Builds a page from the Spring Data page object.
     *
     * @param page Spring Data page
     * @param <T>  type of the items
     * @return the page in the contract shape
     */
    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
