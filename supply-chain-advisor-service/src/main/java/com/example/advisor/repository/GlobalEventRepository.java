package com.example.advisor.repository;

import com.example.advisor.model.GlobalEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GlobalEventRepository extends JpaRepository<GlobalEvent, Long> {

    /** Returns all events that are currently active (ongoing disasters). */
    List<GlobalEvent> findByIsActiveTrue();

    /**
     * Looks up an existing event by location and type so the scheduler can
     * upsert rather than insert a duplicate row.
     */
    Optional<GlobalEvent> findByLocationIgnoreCaseAndEventType(String location, String eventType);
}
