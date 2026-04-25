package com.organizer3.command;

import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * Enqueues javdb enrichment jobs for a named actress.
 *
 * <p>Usage: {@code enrich actress <name>}
 *
 * <p>Finds the actress by name, enqueues a {@code fetch_title} job for every title
 * that lacks staging data, and reports how many jobs were added. The background
 * runner will process them immediately; observe progress via the Logs viewer.
 */
@RequiredArgsConstructor
public class EnrichActressCommand implements Command {

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final EnrichmentQueue queue;

    @Override
    public String name() {
        return "enrich";
    }

    @Override
    public String description() {
        return "Enqueue javdb enrichment jobs: enrich actress <name>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 3 || !"actress".equalsIgnoreCase(args[1])) {
            io.println("Usage: enrich actress <name>");
            return;
        }

        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));

        List<Actress> matches = actressRepo.searchByNamePrefixPaged(name, 10, 0);
        if (matches.isEmpty()) {
            io.println("No actress found matching: " + name);
            return;
        }

        Actress actress;
        if (matches.size() == 1) {
            actress = matches.get(0);
        } else {
            // Use the first exact match, or fall back to the first result
            Optional<Actress> exact = matches.stream()
                    .filter(a -> a.getCanonicalName().equalsIgnoreCase(name))
                    .findFirst();
            actress = exact.orElse(matches.get(0));
            if (exact.isEmpty()) {
                io.println("Multiple matches — using: " + actress.getCanonicalName());
            }
        }

        List<Title> titles = titleRepo.findByActress(actress.getId());
        if (titles.isEmpty()) {
            io.println("No titles found for " + actress.getCanonicalName());
            return;
        }

        int enqueued = 0;
        for (Title title : titles) {
            queue.enqueueTitle(title.getId(), actress.getId());
            enqueued++;
        }

        io.println(String.format("Enqueued %d fetch_title jobs for %s — watch logs for progress",
                enqueued, actress.getCanonicalName()));
    }
}
