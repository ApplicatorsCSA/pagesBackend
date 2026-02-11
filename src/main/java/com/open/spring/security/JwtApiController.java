package com.open.spring.security;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@CrossOrigin
public class JwtApiController {

	@Autowired
	private AuthenticationManager authenticationManager;

	@Autowired
	private JwtTokenUtil jwtTokenUtil;

	@Autowired
	private PersonDetailsService personDetailsService;

	@Value("${jwt.cookie.secure:true}")  // Defaults to production setting if property not found
	private boolean cookieSecure;

	@Value("${jwt.cookie.same-site:None}")  // Defaults to production setting if property not found
	private String cookieSameSite;

	@Value("${jwt.cookie.max-age:43200}")  // 12 hours
	private long cookieMaxAge;

	@PostMapping("/authenticate")
	public ResponseEntity<?> createAuthenticationToken(@RequestBody Person authenticationRequest) throws Exception {
		try {
			authenticate(authenticationRequest.getUid(), authenticationRequest.getPassword());
		} catch (Exception e) {
			return new ResponseEntity<>("Authentication failed: " + e.getMessage(), HttpStatus.UNAUTHORIZED);
		}
		
		final UserDetails userDetails = personDetailsService
				.loadUserByUsername(authenticationRequest.getUid());

		// Get the roles of the user
		List<String> roles = userDetails.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.collect(Collectors.toList());

		// Generate the token with the roles
		final String token = jwtTokenUtil.generateToken(userDetails, roles);

		if (token == null) {
			return new ResponseEntity<>("Token generation failed", HttpStatus.INTERNAL_SERVER_ERROR);
		}

		// Build cookie with development-friendly settings
		// For localhost: allow HTTP and SameSite=Lax
		// For production: require HTTPS and SameSite=None; Secure
		ResponseCookie tokenCookie = ResponseCookie.from("jwt_java_spring", token)
			.httpOnly(false)
			.secure(cookieSecure)  // Configured via jwt.cookie.secure in application.properties
			.path("/")
			.maxAge(cookieMaxAge)  // Configured via jwt.cookie.max-age in application.properties
			.sameSite(cookieSameSite)  // Configured via jwt.cookie.same-site in application.properties
			.build();

		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, tokenCookie.toString()).body(authenticationRequest.getUid() + " was authenticated successfully");
	}

	private void authenticate(String username, String password) throws Exception {
		try {
			authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
		} catch (DisabledException e) {
			throw new Exception("USER_DISABLED", e);
		} catch (BadCredentialsException e) {
			throw new Exception("INVALID_CREDENTIALS", e);
		} catch (Exception e) {
			throw new Exception(e);
		}
	}
	@RestController
	public class CustomLogoutController {

    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
	
		@PostMapping("/api/logout")
		public String performLogout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
			// Perform logout using SecurityContextLogoutHandler
			logoutHandler.logout(request, response, authentication);
	
			// Expire the JWT token immediately by setting a past expiration date
		ResponseCookie cookie = ResponseCookie.from("jwt_java_spring", "")
				.secure(cookieSecure)  // Configured via jwt.cookie.secure in application.properties
				.path("/")
				.maxAge(0)  // Set maxAge to 0 to expire the cookie immediately
				.sameSite(cookieSameSite)  // Configured via jwt.cookie.same-site in application.properties
				.build();
	
			// Set the cookie in the response to effectively "remove" the JWT
			response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	
			// Optional: You can also clear the "Authorization" header if needed
			response.setHeader("Authorization", null);
	
			// Redirect user to home page after logout
			return "redirect:/home";
		}
}

}




	

