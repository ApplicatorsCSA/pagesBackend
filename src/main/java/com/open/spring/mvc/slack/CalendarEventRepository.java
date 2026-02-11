package com.open.spring.mvc.slack;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

// Registering JPA Repository and table contents
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {
    List<CalendarEvent> findByDate(LocalDate date); // Method to find calendar events by date
    List<CalendarEvent> findByDateBetween(LocalDate startDate, LocalDate endDate); // Method to find all calendar events between dates
    List<CalendarEvent> findAll();
    Optional<CalendarEvent> findByTitle(String title); // Optional is fine here
    Optional<CalendarEvent> findByTitleAndDate(String title, LocalDate date); // Find duplicate by title and date
}
