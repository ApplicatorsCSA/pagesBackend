package com.open.spring.mvc.slack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CalendarEventService {

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @Autowired
    private SlackService slackService;

    // Save a new event
    public CalendarEvent saveEvent(CalendarEvent event) {
        CalendarEvent savedEvent = calendarEventRepository.save(event);
        return savedEvent;
    }

    // Find event by title and date (for duplicate detection)
    public CalendarEvent findByTitleAndDate(String title, LocalDate date) {
        return calendarEventRepository.findByTitleAndDate(title, date).orElse(null);
    }

    // Create a calendar event
    public void createCalendarEvent(String title, LocalDate eventDate, String description, String type, String period) {
        CalendarEvent event = new CalendarEvent();
        event.setTitle(title);
        event.setDate(eventDate);
        event.setDescription(description);
        event.setType(type);
        event.setPeriod(period);
        calendarEventRepository.save(event);
    }

    // Get events by a specific date
    public List<CalendarEvent> getEventsByDate(LocalDate date) {
        return calendarEventRepository.findByDate(date);
    }

    // Update event by id
    public boolean updateEventById(int id, String newTitle, String description, LocalDate date, String period) {
        CalendarEvent event = getEventById(id);
        if (event != null) {
            event.setTitle(newTitle);
            event.setDescription(description);
            event.setDate(date);
            event.setPeriod(period);
            calendarEventRepository.save(event);
            return true;
        }
        return false;
    }

    // Delete event by id
    public boolean deleteEventById(int id) {
        CalendarEvent event = getEventById(id);
        if (event != null) {
            calendarEventRepository.delete(event);
            return true;
        }
        return false;
    }

    // Delete event by title
    public boolean deleteEventByTitle(String title) {
        List<CalendarEvent> allEvents = calendarEventRepository.findAll(); 
        List<CalendarEvent> eventsToDelete = allEvents.stream()
                .filter(event -> event.getTitle().equals(title))
                .toList();

        if (!eventsToDelete.isEmpty()) {
            eventsToDelete.forEach(calendarEventRepository::delete);
            return true;
        }
        return false;
    }

    // Retrieve all events
    public List<CalendarEvent> getAllEvents() {
        return calendarEventRepository.findAll();
    }

    public List<CalendarEvent> getEventsWithinDateRange(LocalDate startDate, LocalDate endDate) {
        return calendarEventRepository.findByDateBetween(startDate, endDate);
    }

    // Get event by id
    public CalendarEvent getEventById(int id) {
        return calendarEventRepository.findById((long) id).orElse(null);
    }

    // Parse Slack message and create events
    public void parseSlackMessage(Map<String, String> jsonMap, LocalDate weekStartDate) {
        List<CalendarEvent> events = extractEventsFromText(jsonMap, weekStartDate);
        for (CalendarEvent event : events) {
            saveEvent(event);
        }
    }

    private final String CSP_CHANNEL_ID = "CUS8E3M6Z";
    private final String CSA_CHANNEL_ID = "CRRJL1F1D";
    private final String CSSE_CHANNEL_ID = "C05MNRWC2A1";

    private List<CalendarEvent> extractEventsFromText(Map<String, String> jsonMap, LocalDate weekStartDate) {
        String text = jsonMap.get("text");
        // Use SlackService to determine the correct week start date from the message
        LocalDate parsedWeekStartDate = null;
        try {
            java.lang.reflect.Method method = SlackService.class.getDeclaredMethod("getWeekStartDateFromMessage", String.class);
            method.setAccessible(true);
            parsedWeekStartDate = (LocalDate) method.invoke(slackService, text);
        } catch (Exception e) {
            // Handle exception or log as needed
        }
        if (parsedWeekStartDate != null) {
            weekStartDate = parsedWeekStartDate;
        }
        List<CalendarEvent> events = new ArrayList<>();
        Pattern dayPattern = Pattern.compile("\\[(Mon|Tue|Wed|Thu|Fri|Sat|Sun)(?: - (Mon|Tue|Wed|Thu|Fri|Sat|Sun))?\\]:\\s*(\\*\\*|\\*)?\\s*(.+)");
        Pattern descriptionPattern = Pattern.compile("(\\*\\*|\\*)?\\s*\\u2022\\s*(.+)");
        String[] lines = text.split("\\n");
        CalendarEvent lastEvent = null;
        List<CalendarEvent> lastEventRange = new ArrayList<>();

        for (String line : lines) {
            Matcher dayMatcher = dayPattern.matcher(line);

            if (dayMatcher.find()) {
                String startDay = dayMatcher.group(1);
                String endDay = dayMatcher.group(2) != null ? dayMatcher.group(2) : startDay;
                String asterisks = dayMatcher.group(3);
                String currentTitle = dayMatcher.group(4).trim();
                String period = "0";
                switch(jsonMap.get("channel")) {
                    case CSP_CHANNEL_ID:
                        period = "CSP";
                        break;
                    case CSA_CHANNEL_ID:
                        period = "CSA";
                        break;
                    case CSSE_CHANNEL_ID:
                        period = "CSSE";
                        break;
                }

                String type = "daily plan";
                if ("*".equals(asterisks)) {
                    type = "check-in";
                } else if ("**".equals(asterisks)) {
                    type = "grade";
                }

                // Find description for this line (if any)
                Matcher descMatcher = descriptionPattern.matcher(line);
                String description = "";
                if (descMatcher.find()) {
                    description = descMatcher.group(2).trim();
                }

                lastEventRange.clear(); // Clear previous range
                for (LocalDate date : getDatesInRange(startDay, endDay, weekStartDate)) {
                    CalendarEvent event = new CalendarEvent(date, currentTitle, description, type, period);
                    events.add(event);
                    lastEvent = event; // Update lastEvent to the current event
                    lastEventRange.add(event); // Add to current range
                }
            } else {
                Matcher descMatcher = descriptionPattern.matcher(line);
                if (descMatcher.find() && !lastEventRange.isEmpty()) {
                    String description = descMatcher.group(2).trim();
                    String asterisks = descMatcher.group(1);

                    String type = lastEvent.getType();
                    if ("*".equals(asterisks)) {
                        type = "check-in";
                    } else if ("**".equals(asterisks)) {
                        type = "grade";
                    }

                    for (CalendarEvent event : lastEventRange) {
                        event.setDescription(event.getDescription() +
                                (event.getDescription().isEmpty() ? "" : ", ") +
                                description);
                        event.setType(type);
                    }
                }
            }
        }

        // Log the events and their descriptions
        for (CalendarEvent event : events) {
            System.out.println("Event: " + event.getDate() + ", Title: " + event.getTitle() + ", Description: " + event.getDescription());
        }

        return events;
    }

    private List<LocalDate> getDatesInRange(String startDay, String endDay, LocalDate weekStartDate) {
        List<String> days = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
        int startIndex = days.indexOf(startDay);
        int endIndex = days.indexOf(endDay);

        List<LocalDate> dateRange = new ArrayList<>();
        if (startIndex != -1 && endIndex != -1) {
            for (int i = startIndex; i <= endIndex; i++) {
                dateRange.add(weekStartDate.plusDays(i - weekStartDate.getDayOfWeek().getValue() + 1));
            }
        }
        return dateRange;
    }
}
