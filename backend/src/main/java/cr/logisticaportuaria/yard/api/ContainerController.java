package cr.logisticaportuaria.yard.api;

import cr.logisticaportuaria.shared.api.PageResponse;
import cr.logisticaportuaria.yard.api.dto.ContainerResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.ContainerRequest;
import cr.logisticaportuaria.yard.application.MasterDataService;
import cr.logisticaportuaria.yard.application.YardQueryService;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import jakarta.validation.Valid;
import java.time.Instant;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the containers of the yard.
 */
@RestController
@RequestMapping("/api/v1/containers")
public class ContainerController {

    private final YardQueryService yardQueryService;
    private final MasterDataService masterDataService;

    /**
     * Constructor.
     *
     * @param yardQueryService service of the queries
     * @param masterDataService service of the master data
     */
    public ContainerController(YardQueryService yardQueryService, MasterDataService masterDataService) {
        this.yardQueryService = yardQueryService;
        this.masterDataService = masterDataService;
    }

    /**
     * Lists the containers with the given filters.
     *
     * @param codePart   part of the BIC code (optional)
     * @param status     status of the container (optional)
     * @param clientCode client that owns the container (optional)
     * @param from       lower bound of the ETD (optional)
     * @param to         upper bound of the ETD (optional)
     * @param page       zero based page number
     * @param size       size of the page
     * @return the page of containers
     */
    @GetMapping
    public PageResponse<ContainerResponse> list(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) ContainerStatus status,
            @RequestParam(required = false) String clientCode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return yardQueryService.listContainers(
                code,
                status,
                clientCode,
                from,
                to,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 200)));
    }

    /**
     * Returns a container by its BIC code.
     *
     * @param code BIC code
     * @return the container
     */
    @GetMapping("/{code}")
    public ContainerResponse get(@PathVariable String code) {
        return yardQueryService.getContainer(code);
    }

    /**
     * Registers a container.
     *
     * @param request body of the container
     * @return the created container
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<ContainerResponse> create(@Valid @RequestBody ContainerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.createContainer(request));
    }

    /**
     * Updates a container.
     *
     * @param code    BIC code of the container
     * @param request body of the container
     * @return the updated container
     */
    @PutMapping("/{code}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ContainerResponse update(@PathVariable String code, @Valid @RequestBody ContainerRequest request) {
        return masterDataService.updateContainer(code, request);
    }
}
