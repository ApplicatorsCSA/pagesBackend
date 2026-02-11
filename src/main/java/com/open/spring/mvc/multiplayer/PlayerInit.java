package com.open.spring.mvc.multiplayer;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Random;

@Configuration
public class PlayerInit {

    @Bean
    CommandLineRunner initGamers(PlayerRepository repository) {
        return args -> {
            try {
                if (repository.count() == 0) {

                    int count = 50;
                    String[] uids = new String[count];
                    for (int i = 0; i < count; i++) {
                        uids[i] = "player" + (i + 1);
                    }

                    Random random = new Random();

                    for (String uid : uids) {
                        Player player = new Player();
                        player.setUid(uid);
                        player.setStatus("offline");
                        player.setLevel(random.nextInt(10) + 1);
                        player.setX(random.nextDouble() * 1000.0);
                        player.setY(random.nextDouble() * 1000.0);
                        player.setLastActive(LocalDateTime.now());
                        player.setConnectedAt(LocalDateTime.now());

                        repository.save(player);
                    }

                    System.out.println("Gamer database initialized with "
                            + uids.length + " players");
                }
            } catch (Exception e) {
                // If the players table doesn't exist or any other error occurs,
                // skip gamer initialization rather than failing app startup.
                System.err.println("Skipping gamer initialization due to error: " + e.getMessage());
            }
        };
    }
}