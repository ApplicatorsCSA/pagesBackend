package com.open.spring.mvc.analytics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/ocs-analytics")
@CrossOrigin(origins = {"http://localhost:8080", "http://localhost:4000", "http://127.0.0.1:4100", "http://localhost:4100", "https://open-coding-society.github.io"}, allowCredentials = "true")
public class OCSAnalyticsController {

    @Autowired
    private OCSAnalyticsRepository analyticsRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    /**
     * Save analytics data from frontend
     * POST /api/ocs-analytics/save
     */
    @PostMapping("/save")
    public ResponseEntity<?> saveAnalytics(
            @RequestBody OCSAnalyticsDTO dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        try {
            // Try to get person from authentication first
            Person person = null;
            
            if (userDetails != null) {
                // User is authenticated - use their UID from auth
                person = personRepository.findByUid(userDetails.getUsername());
            } else if (dto.getUid() != null && !dto.getUid().isEmpty()) {
                // Fallback: use UID from DTO if provided
                person = personRepository.findByUid(dto.getUid());
            }
            
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("User not found. Please provide valid uid or be authenticated.");
            }

            // Create analytics record
            OCSAnalytics analytics = new OCSAnalytics();
            analytics.setPerson(person);
            analytics.setSessionStartTime(dto.getSessionStartTime());
            analytics.setSessionEndTime(dto.getSessionEndTime());
            analytics.setSessionDurationSeconds(dto.getSessionDurationSeconds());
            
            // Content info
            analytics.setQuestName(dto.getQuestName());
            analytics.setModuleName(dto.getModuleName());
            analytics.setLessonNumber(dto.getLessonNumber());
            analytics.setPageTitle(dto.getPageTitle());
            analytics.setPageUrl(dto.getPageUrl());
            
            // User actions
            analytics.setLessonsViewed(dto.getLessonsViewed() != null ? dto.getLessonsViewed() : 0);
            analytics.setModulesViewed(dto.getModulesViewed() != null ? dto.getModulesViewed() : 0);
            analytics.setVideosWatched(dto.getVideosWatched() != null ? dto.getVideosWatched() : 0);
            analytics.setVideosCompleted(dto.getVideosCompleted() != null ? dto.getVideosCompleted() : 0);
            analytics.setCodeExecutions(dto.getCodeExecutions() != null ? dto.getCodeExecutions() : 0);
            analytics.setCopyPasteAttempts(dto.getCopyPasteAttempts() != null ? dto.getCopyPasteAttempts() : 0);
            analytics.setQuestionsAnswered(dto.getQuestionsAnswered() != null ? dto.getQuestionsAnswered() : 0);
            analytics.setQuestionsCorrect(dto.getQuestionsCorrect() != null ? dto.getQuestionsCorrect() : 0);
            analytics.calculateAccuracy();
            analytics.setExercisesAttempted(dto.getExercisesAttempted() != null ? dto.getExercisesAttempted() : 0);
            analytics.setExercisesCompleted(dto.getExercisesCompleted() != null ? dto.getExercisesCompleted() : 0);
            analytics.setAssessmentsAttempted(dto.getAssessmentsAttempted() != null ? dto.getAssessmentsAttempted() : 0);
            analytics.setAssessmentAverageScore(dto.getAssessmentAverageScore() != null ? dto.getAssessmentAverageScore() : 0.0);
            
            // Progression
            analytics.setModuleCompleted(dto.getModuleCompleted() != null ? dto.getModuleCompleted() : false);
            analytics.setProgressPercentage(dto.getProgressPercentage() != null ? dto.getProgressPercentage() : 0);
            
            // Engagement
            analytics.setScrollDepthPercentage(dto.getScrollDepthPercentage() != null ? dto.getScrollDepthPercentage() : 0);
            analytics.setHoverEventsCount(dto.getHoverEventsCount() != null ? dto.getHoverEventsCount() : 0);
            analytics.setKeyboardInputEvents(dto.getKeyboardInputEvents() != null ? dto.getKeyboardInputEvents() : 0);
            analytics.setMouseClicksCount(dto.getMouseClicksCount() != null ? dto.getMouseClicksCount() : 0);
            
            // Performance
            analytics.setPageLoadTimeMs(dto.getPageLoadTimeMs() != null ? dto.getPageLoadTimeMs() : 0.0);
            analytics.setTimeoutErrors(dto.getTimeoutErrors() != null ? dto.getTimeoutErrors() : 0);
            analytics.setValidationErrors(dto.getValidationErrors() != null ? dto.getValidationErrors() : 0);
            
            // Metadata
            analytics.setUserAgent(dto.getUserAgent());
            analytics.setReferrer(dto.getReferrer());
            
            // Save
            OCSAnalytics saved = analyticsRepository.save(analytics);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error saving analytics: " + e.getMessage());
        }
    }

    /**
     * Get all analytics for current user
     * GET /api/ocs-analytics/user
     */
    @GetMapping("/user")
    public ResponseEntity<?> getUserAnalytics(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            List<OCSAnalytics> analytics = analyticsRepository.findByPersonOrderBySessionStartTimeDesc(person);
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching analytics: " + e.getMessage());
        }
    }

    /**
     * Get analytics summary for current user
     * GET /api/ocs-analytics/user/summary
     */
    @GetMapping("/user/summary")
    public ResponseEntity<?> getUserSummary(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            Map<String, Object> summary = new HashMap<>();
            
            // Total metrics
            Long totalSeconds = analyticsRepository.getTotalTimeSpentSeconds(person).orElse(0L);
            summary.put("totalTimeSpentSeconds", totalSeconds);
            summary.put("totalTimeFormatted", formatSeconds(totalSeconds));
            
            Double avgDuration = analyticsRepository.getAverageSessionDuration(person).orElse(0.0);
            summary.put("averageSessionDurationSeconds", avgDuration);
            
            Integer totalLessons = analyticsRepository.getTotalLessonsViewed(person).orElse(0);
            summary.put("totalLessonsViewed", totalLessons);
            
            Integer totalModules = analyticsRepository.getTotalModulesViewed(person).orElse(0);
            summary.put("totalModulesViewed", totalModules);
            
            Integer totalCopyPaste = analyticsRepository.getTotalCopyPasteAttempts(person).orElse(0);
            summary.put("totalCopyPasteAttempts", totalCopyPaste);
            
            Double avgAccuracy = analyticsRepository.getAverageAccuracy(person).orElse(0.0);
            summary.put("averageAccuracyPercentage", avgAccuracy);
            
            List<String> quests = analyticsRepository.getEngagedQuests(person);
            summary.put("engagedQuests", quests);
            
            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching summary: " + e.getMessage());
        }
    }

    /**
     * Get analytics for a specific quest
     * GET /api/ocs-analytics/user/quest/{questName}
     */
    @GetMapping("/user/quest/{questName}")
    public ResponseEntity<?> getQuestAnalytics(
            @PathVariable String questName,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            List<OCSAnalytics> analytics = analyticsRepository.findByPersonAndQuestName(person, questName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("questName", questName);
            response.put("sessions", analytics);
            response.put("totalSessions", analytics.size());
            
            Long totalSeconds = analytics.stream()
                    .mapToLong(OCSAnalytics::getSessionDurationSeconds)
                    .sum();
            response.put("totalTimeSpentSeconds", totalSeconds);
            response.put("totalTimeFormatted", formatSeconds(totalSeconds));
            
            Integer totalLessons = analytics.stream()
                    .mapToInt(a -> a.getLessonsViewed() != null ? a.getLessonsViewed() : 0)
                    .sum();
            response.put("totalLessonsViewed", totalLessons);
            
            Integer totalCopyPaste = analytics.stream()
                    .mapToInt(a -> a.getCopyPasteAttempts() != null ? a.getCopyPasteAttempts() : 0)
                    .sum();
            response.put("totalCopyPasteAttempts", totalCopyPaste);
            
            boolean completed = analyticsRepository.isQuestCompleted(person, questName);
            response.put("questCompleted", completed);
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching quest analytics: " + e.getMessage());
        }
    }

    /**
     * Get detailed analytics for a specific module/lesson
     * GET /api/ocs-analytics/user/quest/{questName}/module/{moduleName}
     */
    @GetMapping("/user/quest/{questName}/module/{moduleName}")
    public ResponseEntity<?> getModuleAnalytics(
            @PathVariable String questName,
            @PathVariable String moduleName,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            List<OCSAnalytics> analytics = analyticsRepository
                    .findByPersonAndQuestNameAndModuleName(person, questName, moduleName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("questName", questName);
            response.put("moduleName", moduleName);
            response.put("sessions", analytics);
            response.put("sessionCount", analytics.size());
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching module analytics: " + e.getMessage());
        }
    }

    /**
     * Get analytics for a date range
     * GET /api/ocs-analytics/user/range?start=2025-01-01&end=2025-01-31
     */
    @GetMapping("/user/range")
    public ResponseEntity<?> getAnalyticsRange(
            @RequestParam String start,
            @RequestParam String end,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            Person person = personRepository.findByUid(userDetails.getUsername());
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
            }

            LocalDateTime startTime = LocalDateTime.parse(start + "T00:00:00");
            LocalDateTime endTime = LocalDateTime.parse(end + "T23:59:59");

            List<OCSAnalytics> analytics = analyticsRepository
                    .findByPersonAndSessionStartTimeGreaterThanAndSessionStartTimeLessThan(person, startTime, endTime);
            
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching analytics range: " + e.getMessage());
        }
    }

    /**
     * Get admin analytics for a specific user
     * GET /api/ocs-analytics/admin/user/{userId}
     */
    @GetMapping("/admin/user/{userId}")
    public ResponseEntity<?> getAdminUserAnalytics(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }

        try {
            // TODO: Add role check to verify user is admin
            Person person = personRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<OCSAnalytics> analytics = analyticsRepository.findByPersonOrderBySessionStartTimeDesc(person);
            return ResponseEntity.ok(analytics);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching admin analytics: " + e.getMessage());
        }
    }

    /**
     * Helper method to format seconds into human-readable format
     */
    private String formatSeconds(Long totalSeconds) {
        if (totalSeconds == null || totalSeconds == 0) return "0m";
        
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
