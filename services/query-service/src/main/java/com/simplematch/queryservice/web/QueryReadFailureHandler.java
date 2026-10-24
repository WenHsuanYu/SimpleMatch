package com.simplematch.queryservice.web;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps projection and transaction failures out of Query read HTTP contracts. */
@RestControllerAdvice(
    assignableTypes = {
      QueryReadController.class, QueryAccountReadController.class, QueryOrderReadController.class
    })
public final class QueryReadFailureHandler {
  /** Fails closed without disclosing resource identifiers, SQL or connection details. */
  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Void> unavailableProjection() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
  }
}
