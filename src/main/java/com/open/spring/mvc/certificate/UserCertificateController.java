package com.open.spring.mvc.certificate;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.grades.Grade;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.validation.Valid;

/**
 * Request body for direct certificate assignment (admin use)
 */
class UserCertificateRequestBody {
    public Long personId;
    public Long certificateId;
}

/**
 * Request body for certificate request based on assignment completion
 */
class CertificateRequestDTO {
    public List<String> formativeAssignments;
    public List<String> summativeAssignments;
    public String sprintName;
    public Long certificateId;
}

@RestController
@RequestMapping("/api/user-certificates")
public class UserCertificateController {

    private static final double EXCELLENCE_THRESHOLD = 88.0;
    private static final double COMPLETION_THRESHOLD = 70.0;

    @Autowired
    private UserCertificateRepository userCertificateRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private CertificateRepository certificateRepository;

    @GetMapping
    public List<UserCertificate> getAllUserCertificates() {
        return userCertificateRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserCertificate> getUserCertificate(@PathVariable Long id) {
        Optional<UserCertificate> userCertificate = userCertificateRepository.findById(id);
        return ResponseEntity.of(userCertificate);
    }

    @GetMapping("/person/{personId}")
    public List<UserCertificate> getCertificatesByPersonId(@PathVariable Long personId) {
        return userCertificateRepository.findByPersonId(personId);
    }

    @GetMapping("/certificate/{certificateId}")
    public List<UserCertificate> getUsersByCertificateId(@PathVariable Long certificateId) {
        return userCertificateRepository.findByCertificateId(certificateId);
    }

    @PostMapping("/create")
    public ResponseEntity<?> createUserCertificate(@Valid @RequestBody UserCertificateRequestBody requestBody) {
        if (requestBody.personId == null || requestBody.certificateId == null) {
            return new ResponseEntity<>("Person ID and Certificate ID must be provided", HttpStatus.BAD_REQUEST);
        }

        Optional<Person> personOpt = personRepository.findById(requestBody.personId);
        if (!personOpt.isPresent()) {
            return new ResponseEntity<>("Person not found", HttpStatus.NOT_FOUND);
        }

        Optional<Certificate> certificateOpt = certificateRepository.findById(requestBody.certificateId);
        if (!certificateOpt.isPresent()) {
            return new ResponseEntity<>("Certificate not found", HttpStatus.NOT_FOUND);
        }

        UserCertificate newUserCertificate = new UserCertificate(personOpt.get(), certificateOpt.get());
        userCertificateRepository.save(newUserCertificate);
        return new ResponseEntity<>(newUserCertificate, HttpStatus.CREATED);
    }

    /**
     * Request a certificate based on assignment completion.
     * 
     * The endpoint receives:
     * - List of formative assignments
     * - List of summative assignments  
     * - Sprint name
     * - Certificate ID
     * 
     * Certificate awarding logic:
     * - Average score >= 88%: EXCELLENCE certificate
     * - Average score >= 70% but < 88%: COMPLETION certificate
     * - Average score < 70%: No certificate awarded
     * 
     * @param requestBody The certificate request containing assignment lists and sprint info
     * @param authentication The authenticated user requesting the certificate
     * @return ResponseEntity with the awarded certificate or rejection reason
     */
    @PostMapping("/request")
    public ResponseEntity<?> requestUserCertificate(
            @Valid @RequestBody CertificateRequestDTO requestBody,
            Authentication authentication) {
        
        // Check authentication
        if (authentication == null || !authentication.isAuthenticated()) {
            return new ResponseEntity<>("User must be authenticated", HttpStatus.UNAUTHORIZED);
        }

        // Validate request
        if (requestBody.certificateId == null) {
            return new ResponseEntity<>("Certificate ID is required", HttpStatus.BAD_REQUEST);
        }
        if (requestBody.sprintName == null || requestBody.sprintName.trim().isEmpty()) {
            return new ResponseEntity<>("Sprint name is required", HttpStatus.BAD_REQUEST);
        }

        // Get the authenticated user
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String uid = userDetails.getUsername();
        
        Person person = personRepository.findByUid(uid);
        if (person == null) {
            return new ResponseEntity<>("Person not found", HttpStatus.NOT_FOUND);
        }

        // Get the certificate
        Optional<Certificate> certificateOpt = certificateRepository.findById(requestBody.certificateId);
        if (!certificateOpt.isPresent()) {
            return new ResponseEntity<>("Certificate not found", HttpStatus.NOT_FOUND);
        }
        Certificate certificate = certificateOpt.get();

        // Combine all assignments
        List<String> allAssignments = new ArrayList<>();
        if (requestBody.formativeAssignments != null) {
            allAssignments.addAll(requestBody.formativeAssignments);
        }
        if (requestBody.summativeAssignments != null) {
            allAssignments.addAll(requestBody.summativeAssignments);
        }

        if (allAssignments.isEmpty()) {
            return new ResponseEntity<>("At least one assignment is required", HttpStatus.BAD_REQUEST);
        }

        // Fetch grades from the person's JSON field
        List<Grade> userGrades = new ArrayList<>();
        List<Map<String, Object>> gradesJson = person.getGradesJson();
        if (gradesJson != null) {
            for (Map<String, Object> m : gradesJson) {
                String assignment = m.get("assignment") == null ? null : String.valueOf(m.get("assignment"));
                if (allAssignments.contains(assignment)) {
                    Grade g = new Grade();
                    g.setUid(uid);
                    g.setAssignment(assignment);
                    Object scoreObj = m.get("score");
                    if (scoreObj != null) {
                        try {
                            g.setScore(Double.valueOf(String.valueOf(scoreObj)));
                        } catch (NumberFormatException e) {
                            g.setScore(null);
                        }
                    }
                    userGrades.add(g);
                }
            }
        }

        // Check if all assignments have been completed
        List<String> completedAssignments = new ArrayList<>();
        List<String> missingAssignments = new ArrayList<>();
        
        for (String assignment : allAssignments) {
            boolean found = userGrades.stream()
                .anyMatch(g -> g.getAssignment().equals(assignment) && g.getScore() != null);
            if (found) {
                completedAssignments.add(assignment);
            } else {
                missingAssignments.add(assignment);
            }
        }

        if (!missingAssignments.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "INCOMPLETE");
            response.put("message", "Missing grades for some assignments");
            response.put("missingAssignments", missingAssignments);
            response.put("completedAssignments", completedAssignments);
            return new ResponseEntity<>(response, HttpStatus.OK);
        }

        // Calculate average score
        double totalScore = 0.0;
        int gradeCount = 0;
        for (Grade grade : userGrades) {
            if (allAssignments.contains(grade.getAssignment()) && grade.getScore() != null) {
                totalScore += grade.getScore();
                gradeCount++;
            }
        }
        
        double averageScore = gradeCount > 0 ? totalScore / gradeCount : 0.0;

        // Determine certificate type
        CertificateType awardedType = null;
        if (averageScore >= EXCELLENCE_THRESHOLD) {
            awardedType = CertificateType.EXCELLENCE;
        } else if (averageScore >= COMPLETION_THRESHOLD) {
            awardedType = CertificateType.COMPLETION;
        }

        // Check if user doesn't qualify
        if (awardedType == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "NOT_QUALIFIED");
            response.put("message", "Average score is below the minimum threshold of " + COMPLETION_THRESHOLD + "%");
            response.put("averageScore", Math.round(averageScore * 100.0) / 100.0);
            response.put("requiredMinimum", COMPLETION_THRESHOLD);
            return new ResponseEntity<>(response, HttpStatus.OK);
        }

        // Check if user already has this certificate for this sprint
        List<UserCertificate> existingCerts = userCertificateRepository.findByPersonId(person.getId());
        for (UserCertificate existingCert : existingCerts) {
            if (existingCert.getCertificate().getId().equals(certificate.getId()) 
                && existingCert.getSprintName().equals(requestBody.sprintName)) {
                
                // User already has certificate for this sprint - check if upgrade is possible
                if (existingCert.getCertificateType() == CertificateType.COMPLETION 
                    && awardedType == CertificateType.EXCELLENCE) {
                    // Upgrade from COMPLETION to EXCELLENCE
                    existingCert.setCertificateType(CertificateType.EXCELLENCE);
                    existingCert.setAverageScore(averageScore);
                    existingCert.setDateIssued(new Date());
                    userCertificateRepository.save(existingCert);
                    
                    Map<String, Object> response = new HashMap<>();
                    response.put("status", "UPGRADED");
                    response.put("message", "Certificate upgraded from Completion to Excellence!");
                    response.put("certificate", existingCert);
                    response.put("certificateType", awardedType.getDisplayName());
                    response.put("averageScore", Math.round(averageScore * 100.0) / 100.0);
                    return new ResponseEntity<>(response, HttpStatus.OK);
                }
                
                // Already has same or better certificate
                Map<String, Object> response = new HashMap<>();
                response.put("status", "ALREADY_EARNED");
                response.put("message", "You already have this certificate for " + requestBody.sprintName);
                response.put("existingCertificate", existingCert);
                response.put("existingType", existingCert.getCertificateType().getDisplayName());
                return new ResponseEntity<>(response, HttpStatus.OK);
            }
        }

        // Award the certificate
        UserCertificate newUserCertificate = new UserCertificate(
            person, 
            certificate, 
            awardedType, 
            requestBody.sprintName, 
            averageScore
        );
        userCertificateRepository.save(newUserCertificate);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "AWARDED");
        response.put("message", "Congratulations! You earned the " + awardedType.getDisplayName() + " certificate!");
        response.put("certificate", newUserCertificate);
        response.put("certificateType", awardedType.getDisplayName());
        response.put("sprintName", requestBody.sprintName);
        response.put("averageScore", Math.round(averageScore * 100.0) / 100.0);
        
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
    
    
    @PutMapping("/update/{id}")
    public ResponseEntity<?> updateUserCertificate(@PathVariable Long id, @Valid @RequestBody UserCertificateRequestBody requestBody) {
        Optional<UserCertificate> existingUserCertificateOpt = userCertificateRepository.findById(id);
        if (!existingUserCertificateOpt.isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        UserCertificate existingUserCertificate = existingUserCertificateOpt.get();

        if (requestBody.personId != null) {
            Optional<Person> personOpt = personRepository.findById(requestBody.personId);
            if (!personOpt.isPresent()) {
                return new ResponseEntity<>("Person not found", HttpStatus.NOT_FOUND);
            }
            existingUserCertificate.setPerson(personOpt.get());
        }

        if (requestBody.certificateId != null) {
            Optional<Certificate> certificateOpt = certificateRepository.findById(requestBody.certificateId);
            if (!certificateOpt.isPresent()) {
                return new ResponseEntity<>("Certificate not found", HttpStatus.NOT_FOUND);
            }
            existingUserCertificate.setCertificate(certificateOpt.get());
        }
        
        userCertificateRepository.save(existingUserCertificate);
        return new ResponseEntity<>(existingUserCertificate, HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<HttpStatus> deleteUserCertificate(@PathVariable Long id) {
        if (!userCertificateRepository.existsById(id)) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        try {
            userCertificateRepository.deleteById(id);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}