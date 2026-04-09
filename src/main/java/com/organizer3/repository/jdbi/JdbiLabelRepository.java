package com.organizer3.repository.jdbi;

import com.organizer3.model.Label;
import com.organizer3.repository.LabelRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class JdbiLabelRepository implements LabelRepository {

    private static final RowMapper<Label> MAPPER = (rs, ctx) ->
            new Label(
                    rs.getString("code"),
                    rs.getString("label_name"),
                    rs.getString("company"),
                    rs.getString("description"),
                    rs.getString("company_description")
            );

    private final Jdbi jdbi;

    @Override
    public Map<String, Label> findAllAsMap() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT code, label_name, company, description, company_description FROM labels")
                        .map(MAPPER)
                        .list()
        ).stream().collect(Collectors.toMap(
                l -> l.code().toUpperCase(),
                l -> l,
                (a, b) -> a   // keep first on duplicate codes
        ));
    }
}
