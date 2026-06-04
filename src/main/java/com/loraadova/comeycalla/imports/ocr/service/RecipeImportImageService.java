package com.loraadova.comeycalla.imports.ocr.service;

import com.loraadova.comeycalla.config.GoogleVisionService;
import com.loraadova.comeycalla.imports.dto.RecipeScanResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RecipeImportImageService {

    @Autowired
    private GoogleVisionService googleVisionService;

    public RecipeScanResponseDto scanImages(List<MultipartFile> images, List<String> sections) {
        if (images.size() == 1 && (sections == null || sections.isEmpty())) {
            String text = googleVisionService.extractText(images.get(0));
            return parseFullRecipeText(text, images.get(0).getOriginalFilename());
        }

        String title = "";
        String description = "";
        Integer cookingTime = null;
        Integer servings = null;
        String difficulty = "medium";

        List<String> ingredients = new ArrayList<>();
        List<String> steps = new ArrayList<>();

        String coverImageName = null;
        StringBuilder rawTextBuilder = new StringBuilder();

        for (int i = 0; i < images.size(); i++) {
            MultipartFile image = images.get(i);
            String section = "";
            if (sections != null && i < sections.size()) {
                section = normalizeSection(sections.get(i));
            }

            if ("cover".equals(section)) {
                coverImageName = image.getOriginalFilename();
                continue;
            }

            String text = googleVisionService.extractText(image);
            List<String> lines = getCleanLines(text);

            rawTextBuilder
                    .append("SECTION: ")
                    .append(section)
                    .append("\n")
                    .append(text)
                    .append("\n\n");

            switch (section) {
                case "main" -> {
                    if (title.isBlank()) {
                        title = extractTitleFromMain(lines);
                    }

                    if (cookingTime == null) {
                        cookingTime = extractCookingTime(lines);
                    }

                    if (servings == null) {
                        servings = extractServings(lines);
                    }

                    difficulty = extractDifficulty(lines);

                    if (description.isBlank()) {
                        description = extractDescriptionFromMain(lines, title);
                    }
                }

                case "ingredients" -> ingredients.addAll(extractIngredients(lines));

                case "steps" -> steps.addAll(extractSteps(lines));

                default -> {
                }
            }
        }

        ingredients = removeDuplicateLines(ingredients);
        steps = removeDuplicateLines(steps);

        return new RecipeScanResponseDto(
                this.cleanParsedItem(title),
                this.cleanParsedItem(description),
                coverImageName,
                cookingTime,
                servings,
                this.cleanParsedItem(difficulty),
                "",
                ingredients.stream()
                        .map(this::cleanParsedItem)
                        .filter(item -> !item.isBlank())
                        .toList(),
                steps.stream()
                        .map(this::cleanParsedItem)
                        .filter(item -> !item.isBlank())
                        .toList(),
                new ArrayList<>(),
                rawTextBuilder.toString().trim()
        );
    }

    private String normalizeSection(String section) {
        if (section == null) {
            return "";
        }

        return section.trim().toLowerCase();
    }

    private List<String> getCleanLines(String rawText) {
        String[] rawLines = rawText.split("\\n");
        List<String> lines = new ArrayList<>();

        for (String rawLine : rawLines) {
            String line = cleanLine(rawLine);

            if (line.isBlank()) {
                continue;
            }

            String lower = normalize(line);

            if (isNoiseLine(lower)) {
                continue;
            }

            lines.add(line);
        }

        return lines;
    }

    private String extractTitleFromMain(List<String> lines) {
        for (String line : lines) {
            String lower = normalize(line);

            if (isMetadataLine(lower)) {
                continue;
            }

            if (isRatingLine(lower)) {
                continue;
            }

            if (isNutritionLine(lower)) {
                continue;
            }

            if (line.length() < 3) {
                continue;
            }

            return line;
        }

        return "";
    }

    private String extractDescriptionFromMain(List<String> lines, String title) {
        List<String> descriptionLines = new ArrayList<>();

        boolean titleSkipped = title == null || title.isBlank();

        for (String line : lines) {
            String lower = normalize(line);

            if (!titleSkipped && line.equals(title)) {
                titleSkipped = true;
                continue;
            }

            if (isMetadataLine(lower)) {
                continue;
            }

            if (isRatingLine(lower)) {
                continue;
            }

            if (isNutritionLine(lower)) {
                continue;
            }

            if (isUiNoiseLine(lower)) {
                continue;
            }

            if (isIngredientsTitle(lower) || isStepsTitle(lower)) {
                continue;
            }

            descriptionLines.add(line);
        }

        return String.join(" ", descriptionLines).trim();
    }

    private List<String> extractIngredients(List<String> lines) {
        List<String> candidateLines = new ArrayList<>();

        for (String line : lines) {
            String lower = normalize(line);

            if (isIngredientsTitle(lower)) {
                continue;
            }

            if (isMetadataLine(lower)) {
                continue;
            }

            if (isUiNoiseLine(lower)) {
                continue;
            }

            if (isNutritionLine(lower)) {
                continue;
            }

            if (lower.contains("cantidad de la racion")) {
                continue;
            }

            if (lower.equals("2") || lower.equals("4")) {
                continue;
            }

            candidateLines.add(cleanIngredientLine(line));
        }

        return processIngredientLines(candidateLines);
    }

    private List<String> extractSteps(List<String> lines) {
        List<String> candidateLines = new ArrayList<>();

        for (String line : lines) {
            String lower = normalize(line);

            if (isStepsTitle(lower)) {
                continue;
            }

            if (isIngredientsTitle(lower)) {
                continue;
            }

            if (isMetadataLine(lower)) {
                continue;
            }

            if (isUiNoiseLine(lower)) {
                continue;
            }

            if (isNutritionLine(lower)) {
                continue;
            }

            candidateLines.add(cleanStepLine(line));
        }

        return mergeBrokenStepLines(candidateLines);
    }

    private List<String> processIngredientLines(List<String> lines) {
        List<String> result = new ArrayList<>();

        int i = 0;

        while (i < lines.size()) {
            String current = lines.get(i).trim();

            if (current.isBlank()) {
                i++;
                continue;
            }

            if (looksLikeOnlyQuantity(current)) {
                List<String> quantities = new ArrayList<>();

                while (i < lines.size() && looksLikeOnlyQuantity(lines.get(i))) {
                    quantities.add(cleanQuantity(lines.get(i)));
                    i++;
                }

                List<String> names = new ArrayList<>();

                while (i < lines.size()
                        && !looksLikeOnlyQuantity(lines.get(i))
                        && names.size() < quantities.size()) {
                    names.add(lines.get(i).trim());
                    i++;
                }

                for (int j = 0; j < quantities.size(); j++) {
                    if (j < names.size()) {
                        result.add((quantities.get(j) + " " + names.get(j)).trim());
                    } else {
                        result.add(quantities.get(j));
                    }
                }

                continue;
            }

            result.add(current);
            i++;
        }

        return mergeBrokenIngredientLines(result);
    }

    private List<String> mergeBrokenIngredientLines(List<String> ingredients) {
        List<String> result = new ArrayList<>();

        for (String ingredient : ingredients) {
            String clean = ingredient.trim();

            if (clean.isBlank()) {
                continue;
            }

            if (!result.isEmpty() && looksLikeIngredientContinuation(clean)) {
                int lastIndex = result.size() - 1;
                result.set(lastIndex, result.get(lastIndex) + " " + clean);
            } else {
                result.add(clean);
            }
        }

        return result;
    }

    private boolean looksLikeIngredientContinuation(String line) {
        String lower = normalize(line);

        if (startsWithQuantity(lower)) {
            return false;
        }

        if (looksLikeOnlyQuantity(line)) {
            return false;
        }

        if (lower.equals("sal")
                || lower.contains("pimienta")
                || lower.contains("aceite")
                || lower.contains("azucar")
                || lower.contains("agua")) {
            return false;
        }

        return line.length() < 28;
    }

    private List<String> mergeBrokenStepLines(List<String> steps) {
        List<String> result = new ArrayList<>();

        for (String step : steps) {
            String clean = step.trim();

            if (clean.isBlank()) {
                continue;
            }

            if (result.isEmpty()) {
                result.add(clean);
                continue;
            }

            String previous = result.get(result.size() - 1);

            if (shouldMergeWithPreviousStep(previous, clean)) {
                result.set(result.size() - 1, previous + " " + clean);
            } else {
                result.add(clean);
            }
        }

        return result;
    }

    private boolean shouldMergeWithPreviousStep(String previous, String current) {
        String previousTrimmed = previous.trim();
        String currentTrimmed = current.trim();

        if (previousTrimmed.endsWith(".")
                || previousTrimmed.endsWith("!")
                || previousTrimmed.endsWith("?")) {
            return false;
        }

        if (startsWithStepNumber(currentTrimmed)) {
            return false;
        }

        return currentTrimmed.length() < 110;
    }

    private Integer extractCookingTime(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = normalize(lines.get(i));

            if (lower.contains("tiempo")
                    || lower.contains("duracion")
                    || lower.contains("preparacion")
                    || lower.contains("coccion")
                    || lower.contains("listo en")
                    || lower.contains("total")) {

                Integer minutes = extractMinutes(lower);

                if (minutes != null) {
                    return minutes;
                }

                if (i + 1 < lines.size()) {
                    return extractMinutes(normalize(lines.get(i + 1)));
                }
            }

            Integer inlineMinutes = extractMinutesFromExplicitTime(lower);
            if (inlineMinutes != null) {
                return inlineMinutes;
            }
        }

        return null;
    }

    private Integer extractMinutesFromExplicitTime(String text) {
        Matcher matcher = Pattern
                .compile("(\\d+)\\s*(min|mins|minuto|minutos|h|hora|horas)")
                .matcher(text);

        if (matcher.find()) {
            return extractMinutes(text);
        }

        return null;
    }

    private Integer extractMinutes(String text) {
        Integer hours = null;
        Integer minutes = null;

        Matcher hourMatcher = Pattern
                .compile("(\\d+)\\s*(h|hora|horas)")
                .matcher(text);

        if (hourMatcher.find()) {
            hours = Integer.parseInt(hourMatcher.group(1));
        }

        Matcher minuteMatcher = Pattern
                .compile("(\\d+)\\s*(m|min|mins|minuto|minutos)")
                .matcher(text);

        if (minuteMatcher.find()) {
            minutes = Integer.parseInt(minuteMatcher.group(1));
        }

        if (hours != null || minutes != null) {
            return (hours == null ? 0 : hours * 60) + (minutes == null ? 0 : minutes);
        }

        Matcher simpleNumberMatcher = Pattern.compile("(\\d+)").matcher(text);

        if (simpleNumberMatcher.find()) {
            return Integer.parseInt(simpleNumberMatcher.group(1));
        }

        return null;
    }

    private Integer extractServings(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = normalize(lines.get(i));

            if (lower.contains("raciones")
                    || lower.contains("racion")
                    || lower.contains("comensales")
                    || lower.contains("comensal")
                    || lower.contains("personas")
                    || lower.contains("persona")
                    || lower.contains("porciones")
                    || lower.contains("servings")
                    || lower.contains("serves")) {

                Matcher matcher = Pattern.compile("(\\d+)").matcher(lower);

                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }

                if (i + 1 < lines.size()) {
                    Matcher nextMatcher = Pattern.compile("(\\d+)").matcher(lines.get(i + 1));
                    if (nextMatcher.find()) {
                        return Integer.parseInt(nextMatcher.group(1));
                    }
                }
            }
        }

        return null;
    }

    private String extractDifficulty(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = normalize(lines.get(i));

            String detected = mapDifficulty(lower);
            if (detected != null) {
                return detected;
            }

            if (lower.contains("dificultad") || lower.contains("difficulty")) {
                if (i + 1 < lines.size()) {
                    detected = mapDifficulty(normalize(lines.get(i + 1)));
                    if (detected != null) {
                        return detected;
                    }
                }
            }
        }

        return "medium";
    }

    private String mapDifficulty(String lower) {
        if (lower.contains("facil") || lower.contains("easy") || lower.contains("baja")) {
            return "facil";
        }

        if (lower.contains("dificil") || lower.contains("hard") || lower.contains("alta")) {
            return "dificil";
        }

        if (lower.contains("media") || lower.contains("medio") || lower.contains("medium")) {
            return "media";
        }

        return null;
    }

    private boolean looksLikeOnlyQuantity(String line) {
        String lower = normalize(line);

        return lower.matches("^\\d+[.,]?\\d*\\s*(g|gr|gramo|gramos|gramo\\(s\\)|kg|ml|mililitro|mililitros|mililitro\\(s\\)|l|litro|litros|sobre|sobres|sobre\\(s\\)|unidad|unidades|unidad\\(es\\)|pizca|pizcas|pizca\\(s\\)|cucharada|cucharadas|cucharada\\(s\\)|cdta|cda)\\.?$")
                || lower.matches("^\\d+/\\d+\\s*(g|gr|kg|ml|l)?\\.?$")
                || lower.matches("^(medio|media|un|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)\\s*(unidad|unidades)?$");
    }

    private boolean startsWithQuantity(String line) {
        return line.matches("^\\d+.*")
                || line.matches("^(medio|media|un|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez).*");
    }

    private boolean startsWithStepNumber(String line) {
        return line.matches("^\\d+[).\\-].*");
    }

    private String cleanQuantity(String quantity) {
        return quantity
                .replace("gramo(s)", "g")
                .replace("gramos", "g")
                .replace("gramo", "g")
                .replace("mililitro(s)", "ml")
                .replace("mililitros", "ml")
                .replace("mililitro", "ml")
                .replace("unidad(es)", "unidad")
                .replace("sobre(s)", "sobre")
                .replace("pizca(s)", "pizca")
                .replace("cucharada(s)", "cucharada")
                .trim();
    }

    private String cleanIngredientLine(String line) {
        return line
                .replaceFirst("^[-•*]\\s*", "")
                .replaceFirst("(?i)^de\\s+", "")
                .trim();
    }

    private String cleanStepLine(String line) {
        return line
                .replaceFirst("^\\d+[).\\-]\\s*", "")
                .replaceFirst("^[-•*]\\s*", "")
                .trim();
    }

    private String cleanLine(String line) {
        return line
                .replace("•", "")
                .replace("*", "")
                .trim();
    }

    private List<String> removeDuplicateLines(List<String> lines) {
        List<String> result = new ArrayList<>();

        for (String line : lines) {
            String clean = cleanLine(line);

            if (clean.isBlank()) {
                continue;
            }

            boolean exists = result.stream()
                    .anyMatch(existing -> normalize(existing).equals(normalize(clean)));

            if (!exists) {
                result.add(clean);
            }
        }

        return result;
    }

    private boolean isIngredientsTitle(String lowerLine) {
        return lowerLine.equals("ingredientes")
                || lowerLine.equals("ingredientes:")
                || lowerLine.equals("ingredients")
                || lowerLine.equals("ingredients:")
                || lowerLine.contains("lista de ingredientes")
                || lowerLine.contains("necesitas")
                || lowerLine.contains("vas a necesitar");
    }

    private boolean isStepsTitle(String lowerLine) {
        return lowerLine.equals("instrucciones")
                || lowerLine.equals("instrucciones:")
                || lowerLine.equals("preparacion")
                || lowerLine.equals("preparacion:")
                || lowerLine.equals("elaboracion")
                || lowerLine.equals("elaboracion:")
                || lowerLine.equals("pasos")
                || lowerLine.equals("pasos:")
                || lowerLine.equals("modo de preparacion")
                || lowerLine.equals("preparation")
                || lowerLine.equals("instructions")
                || lowerLine.equals("steps")
                || lowerLine.equals("metodo")
                || lowerLine.contains("paso a paso")
                || lowerLine.contains("como hacer");
    }

    private boolean isMetadataLine(String lowerLine) {
        return lowerLine.contains("tiempo")
                || lowerLine.contains("duracion")
                || lowerLine.contains("preparacion")
                || lowerLine.contains("coccion")
                || lowerLine.contains("listo en")
                || lowerLine.contains("total")
                || lowerLine.contains("dificultad")
                || lowerLine.contains("difficulty")
                || lowerLine.contains("racion")
                || lowerLine.contains("raciones")
                || lowerLine.contains("comensal")
                || lowerLine.contains("comensales")
                || lowerLine.contains("persona")
                || lowerLine.contains("personas")
                || lowerLine.contains("porciones")
                || lowerLine.contains("servings")
                || lowerLine.contains("serves");
    }

    private boolean isNoiseLine(String lowerLine) {
        return lowerLine.startsWith("#")
                || lowerLine.contains("www.")
                || lowerLine.contains("http")
                || lowerLine.contains("@")
                || lowerLine.contains("copyright");
    }

    private boolean isUiNoiseLine(String lowerLine) {
        return lowerLine.contains("compartir")
                || lowerLine.contains("ahorra")
                || lowerLine.contains("suscribete")
                || lowerLine.contains("registrate")
                || lowerLine.contains("login")
                || lowerLine.contains("iniciar sesion")
                || lowerLine.contains("valoraciones")
                || lowerLine.contains("opiniones")
                || lowerLine.contains("youtube");
    }

    private boolean isRatingLine(String lowerLine) {
        return lowerLine.matches("^\\d+[.,]\\d+\\s*\\(\\d+\\).*")
                || lowerLine.matches("^\\d+[.,]\\d+.*")
                || lowerLine.contains("valoracion")
                || lowerLine.contains("rating");
    }

    private boolean isNutritionLine(String lowerLine) {
        return lowerLine.contains("calorias")
                || lowerLine.contains("kcal")
                || lowerLine.contains("proteinas")
                || lowerLine.contains("grasas")
                || lowerLine.contains("carbohidratos")
                || lowerLine.contains("hidratos")
                || lowerLine.contains("sodio")
                || lowerLine.contains("fibra")
                || lowerLine.contains("azucares");
    }

    private String normalize(String text) {
        return text
                .toLowerCase()
                .replace("á", "a")
                .replace("é", "e")
                .replace("í", "i")
                .replace("ó", "o")
                .replace("ú", "u")
                .replace("ñ", "n")
                .trim();
    }

    private String cleanParsedItem(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replaceAll("^[\\s\\p{Punct}·•●▪]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private RecipeScanResponseDto parseFullRecipeText(String rawText, String coverImageName) {
        List<String> cleanLines = getCleanLines(rawText);
        cleanLines = removeDuplicateLines(cleanLines);

        String title = extractTitleFromMain(cleanLines);
        Integer cookingTime = extractCookingTime(cleanLines);
        Integer servings = extractServings(cleanLines);
        String difficulty = extractDifficulty(cleanLines);

        List<String> mainLines = new ArrayList<>();
        List<String> ingredientLines = new ArrayList<>();
        List<String> stepLines = new ArrayList<>();

        boolean readingIngredients = false;
        boolean readingSteps = false;

        for (String line : cleanLines) {
            String lower = normalize(line);

            if (line.equals(title)) {
                continue;
            }

            if (isMetadataLine(lower) || isRatingLine(lower) || isNutritionLine(lower) || isUiNoiseLine(lower)) {
                continue;
            }

            if (isIngredientsTitle(lower)) {
                readingIngredients = true;
                readingSteps = false;
                continue;
            }

            if (isStepsTitle(lower)) {
                readingIngredients = false;
                readingSteps = true;
                continue;
            }

            if (isStopSection(lower)) {
                readingIngredients = false;
                readingSteps = false;
                continue;
            }

            if (readingIngredients) {
                ingredientLines.add(line);
                continue;
            }

            if (readingSteps) {
                stepLines.add(line);
                continue;
            }

            mainLines.add(line);
        }

        List<String> ingredients = extractIngredients(ingredientLines);
        List<String> steps = extractSteps(stepLines);

        String description = extractDescriptionFromMain(mainLines, title);

        return new RecipeScanResponseDto(
                cleanParsedItem(title),
                cleanParsedItem(description),
                coverImageName,
                cookingTime,
                servings,
                cleanParsedItem(difficulty),
                "",
                ingredients.stream()
                        .map(this::cleanParsedItem)
                        .filter(item -> !item.isBlank())
                        .toList(),
                steps.stream()
                        .map(this::cleanParsedItem)
                        .filter(item -> !item.isBlank())
                        .toList(),
                new ArrayList<>(),
                rawText.trim()
        );
    }

    private boolean isStopSection(String lowerLine) {
        return lowerLine.contains("notas")
                || lowerLine.contains("nota")
                || lowerLine.contains("consejos")
                || lowerLine.contains("tips")
                || lowerLine.contains("informacion nutricional")
                || lowerLine.contains("nutricion")
                || lowerLine.contains("calorias");
    }
}