package com.vms.api.auth.infrastructure;

import com.vms.api.auth.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String email);

  Optional<User> findByGoogleSubject(String googleSubject);

  @Query("select u from User u where u.email = :login or u.username = :login")
  Optional<User> findByLogin(@Param("login") String login);

  boolean existsByEmailOrUsername(String email, String username);
}
