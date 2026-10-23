package qualityfixtures.model;

import org.springframework.jdbc.core.JdbcTemplate;

/** Deliberate negative fixture; never part of the production artifact. */
public class InvalidModel { JdbcTemplate database; }
