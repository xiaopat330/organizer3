package com.organizer3.command;

import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.TitleTable;
import com.organizer3.shell.io.CommandIO;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Actress lookup and detail commands.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code actress find <prefix>} — prefix search across first/last name with interactive picker</li>
 *   <li>{@code actress <name>} — display full detail for a named actress directly</li>
 * </ul>
 */
public class ActressSearchCommand implements Command {

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final LabelRepository labelRepo;

    public ActressSearchCommand(ActressRepository actressRepo,
                                TitleRepository titleRepo,
                                LabelRepository labelRepo) {
        this.actressRepo = actressRepo;
        this.titleRepo = titleRepo;
        this.labelRepo = labelRepo;
    }

    @Override
    public String name() {
        return "actress";
    }

    @Override
    public String description() {
        return "Actress detail and search: actress <name> | actress find <prefix>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: actress <name> | actress find <prefix>");
            return;
        }

        if (args[1].equalsIgnoreCase("find")) {
            handleSearch(args, io);
        } else {
            String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            showDetail(name, io);
        }
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    private void handleSearch(String[] args, CommandIO io) {
        if (args.length < 3 || args[2].isBlank()) {
            io.println("Usage: actress find <prefix>");
            return;
        }

        String prefix = args[2];
        List<Actress> results = actressRepo.searchByNamePrefix(prefix);

        if (results.isEmpty()) {
            io.println("No actresses found matching '" + prefix + "'");
            return;
        }

        List<String> names = results.stream().map(Actress::getCanonicalName).toList();

        if (names.size() == 1) {
            showDetail(names.get(0), io);
            return;
        }

        io.println(names.size() + " actresses match '" + prefix + "':");
        Optional<String> selected = io.pick(names);
        selected.ifPresent(name -> showDetail(name, io));
    }

    // -------------------------------------------------------------------------
    // Detail display
    // -------------------------------------------------------------------------

    private static final String YELLOW = "\033[93m";
    private static final String CYAN   = "\033[96m";
    private static final String GREEN  = "\033[92m";
    private static final String RESET  = "\033[0m";

    private static final java.time.format.DateTimeFormatter DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy");

    private void showDetail(String name, CommandIO io) {
        Optional<Actress> result = actressRepo.resolveByName(name);
        if (result.isEmpty()) {
            io.println("Unknown actress: " + name);
            return;
        }
        Actress actress = result.get();

        List<Title> primaryTitles  = titleRepo.findByActress(actress.getId());
        List<ActressAlias> aliases = actressRepo.findAliases(actress.getId());
        Map<String, Label> labelMap = labelRepo.findAllAsMap();

        List<AliasSection> aliasSections = buildAliasSections(aliases);

        int totalTitles = primaryTitles.size()
                + aliasSections.stream().mapToInt(a -> a.titles().size()).sum();

        List<Title> allTitles = new ArrayList<>(primaryTitles);
        aliasSections.forEach(a -> allTitles.addAll(a.titles()));
        Optional<LocalDate> firstAdded = allTitles.stream()
                .map(Title::addedDate)
                .filter(d -> d != null)
                .min(Comparator.naturalOrder());
        Optional<LocalDate> lastAdded = allTitles.stream()
                .map(Title::addedDate)
                .filter(d -> d != null)
                .max(Comparator.naturalOrder());

        // ── Header ──────────────────────────────────────────────────────────
        String tier    = actress.getTier() != null ? actress.getTier().name() : "N/A";
        String favMark = actress.isFavorite() ? "  ★" : "";
        io.printlnAnsi("  Actress: " + YELLOW + actress.getCanonicalName() + RESET + favMark);
        io.printlnAnsi("  Tier:    " + CYAN + tier + RESET + "  (" + titleCount(totalTitles) + ")");

        String start = firstAdded.map(d -> d.format(DATE_FORMAT)).orElse("?");
        String end   = lastAdded.map(d -> d.format(DATE_FORMAT)).orElse("?");
        io.println("  Active:  " + start + " → " + end);

        // ── Primary titles ───────────────────────────────────────────────────
        io.println();
        printTitlesByCompany(primaryTitles, labelMap, "  ", io);

        // ── Alias sections ───────────────────────────────────────────────────
        if (!aliasSections.isEmpty()) {
            io.println();
            io.println("─── Also known as " + "─".repeat(32));
            for (AliasSection section : aliasSections) {
                io.println();
                io.printlnAnsi("  " + YELLOW + section.aliasName() + RESET
                        + "  (" + titleCount(section.titles().size()) + ")");
                printTitlesByCompany(section.titles(), labelMap, "    ", io);
            }
        }
    }

    private List<AliasSection> buildAliasSections(List<ActressAlias> aliases) {
        List<AliasSection> sections = new ArrayList<>();
        for (ActressAlias alias : aliases) {
            actressRepo.findByCanonicalName(alias.aliasName()).ifPresent(aliasActress -> {
                List<Title> titles = titleRepo.findByActress(aliasActress.getId());
                if (!titles.isEmpty()) {
                    sections.add(new AliasSection(alias.aliasName(), titles));
                }
            });
        }
        return sections;
    }

    private void printTitlesByCompany(List<Title> titles, Map<String, Label> labelMap,
                                      String indent, CommandIO io) {
        if (titles.isEmpty()) return;

        List<TitleTable.Column> columns = List.of(
                TitleTable.Column.colored("Product Code", Title::code, GREEN),
                TitleTable.Column.plain("Added", t -> t.addedDate() != null ? t.addedDate().format(DATE_FORMAT) : ""),
                TitleTable.Column.plain("Label", t -> {
                    String name = labelNameFor(t, labelMap);
                    return name != null ? name : "";
                }),
                TitleTable.Column.plain("Location", t -> t.path() != null ? t.path().toString() : "")
        );

        Map<String, List<Title>> byCompany = titles.stream().collect(
                Collectors.groupingBy(t -> companyFor(t, labelMap),
                        LinkedHashMap::new,
                        Collectors.toList()));

        // Sort alphabetically, "(Unknown)" always last
        Comparator<String> companyOrder = Comparator
                .comparing((String c) -> c.equals("(Unknown)") ? 1 : 0)
                .thenComparing(Comparator.naturalOrder());

        byCompany.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(companyOrder))
                .forEach(entry -> {
                    String company = entry.getKey();
                    List<Title> group = entry.getValue();
                    List<Title> sorted = group.stream()
                            .sorted(Comparator.comparing(Title::addedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparing(Title::code))
                            .toList();
                    io.printlnAnsi(indent + YELLOW + company + RESET);
                    TitleTable.print(sorted, columns, indent + "  ", io);
                    io.println();
                });
    }

    private static String companyFor(Title title, Map<String, Label> labelMap) {
        if (title.label() == null) return "(Unknown)";
        Label label = labelMap.get(title.label().toUpperCase());
        return (label != null && label.company() != null) ? label.company() : "(Unknown)";
    }

    private static String labelNameFor(Title title, Map<String, Label> labelMap) {
        if (title.label() == null) return null;
        Label label = labelMap.get(title.label().toUpperCase());
        return (label != null && label.labelName() != null && !label.labelName().isBlank())
                ? label.labelName() : null;
    }

    private static String titleCount(int n) {
        return n + (n == 1 ? " title" : " titles");
    }

    // -------------------------------------------------------------------------

    private record AliasSection(String aliasName, List<Title> titles) {}
}
