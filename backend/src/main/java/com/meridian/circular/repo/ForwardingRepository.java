package com.meridian.circular.repo;

import com.meridian.circular.domain.Forwarding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Forwarding}. Finders key on the owning circular's
 * surrogate id ({@code circular.id}), scoping results to one workspace's copy.
 */
public interface ForwardingRepository extends JpaRepository<Forwarding, UUID> {

    /** All forwardings for a circular, oldest first. */
    List<Forwarding> findByCircularNoOrderByForwardedAtAsc(String circularNo);
}
