package org.springframework.data.jpa.repository;

import java.util.*;
public interface JpaRepository<T,ID> { Optional<T> findById(ID id); List<T> findAll(); <S extends T> S save(S e); void delete(T e); void deleteById(ID id); long count(); boolean existsById(ID id); }
