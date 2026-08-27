package com.calimport.guias.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.calimport.guias.model.Repartidor;

@Repository
public interface RepartidorRepository extends JpaRepository<Repartidor, Integer> {

    Optional<Repartidor> findByEmail(String email);

    List<Repartidor> findByActivoTrue();

}
