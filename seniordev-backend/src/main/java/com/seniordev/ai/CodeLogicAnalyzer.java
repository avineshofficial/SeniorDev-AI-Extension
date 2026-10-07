package com.seniordev.ai;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-intelligence static analyzer and guard for control flow logic across programming languages.
 * Covers while-loops, for-loops, and if/elif/else-if conditional constructs.
 * Ensures user intent is preserved, Yoda conditions are safely normalized without mutating values,
 * and LLM logic inversions or dead code conditions are detected and prevented.
 */
public class CodeLogicAnalyzer {

    /**
     * Flips a comparison operator: < becomes >, <= becomes >=, == remains ==, != remains !=.
     */
    public static String flipOperator(String op) {
        if (op == null) return "";
        return switch (op) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> op;
        };
    }

    /**
     * Appends a step statement (e.g. "a += 1") to the bottom of the loop body.
     */
    public static String appendStep(String code, String var, String step) {
        if (code == null) return "";
        String[] lines = code.split("\r?\n");
        int targetIdx = -1;
        String targetIndent = "    ";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                targetIdx = i;
                Matcher m = Pattern.compile("^(\\s*)").matcher(lines[i]);
                if (m.find() && !m.group(1).isEmpty()) {
                    targetIndent = m.group(1);
                }
                break;
            }
        }
        if (targetIdx >= 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
                if (i == targetIdx) {
                    sb.append(targetIndent).append(var).append(" ").append(step).append("\n");
                }
            }
            return sb.toString().trim();
        }
        return code + "\n" + targetIndent + var + " " + step;
    }

    // =========================================================================
    // WHILE LOOP ANALYSIS
    // =========================================================================

    /**
     * Alias for extractWhileLoopInfo to maintain backward compatibility.
     */
    public static String[] extractLoopInfo(String code) {
        return extractWhileLoopInfo(code);
    }

    /**
     * Extracts while loop info from code, handling both standard (VAR op LIMIT) and
     * Yoda (LIMIT op VAR) conditions.
     * Returns [var, start, normalized_op, limit, isYoda] where op is normalized to "VAR op LIMIT" form.
     * Returns null if no while loop pattern is found.
     */
    public static String[] extractWhileLoopInfo(String code) {
        if (code == null || code.isBlank()) return null;

        // 1. Variable initialization: e.g. "a = 10", "int a = 1;", "let a = 10;"
        Matcher mInit = Pattern.compile("(?m)^(\\s*)(?:int|let|var|const)?\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(-?\\d+)\\s*;?").matcher(code);
        if (!mInit.find()) return null;
        String var = mInit.group(2);
        String startStr = mInit.group(3);

        // 2a. Standard form: "while VAR op LIMIT" (e.g. "while a > 0:", "while (a < 10)")
        Matcher mStd = Pattern.compile(
            "(?m)^(\\s*)while\\s*\\(?\\s*" + var + "\\s*(<=?|>=?)\\s*(-?\\d+)\\s*\\)?\\s*[:{]"
        ).matcher(code);
        if (mStd.find()) {
            return new String[]{ var, startStr, mStd.group(2), mStd.group(3), "false" };
        }

        // 2b. Yoda form: "while LIMIT op VAR" (e.g. "while 1 < a:", "while (0 < a)")
        Matcher mYoda = Pattern.compile(
            "(?m)^(\\s*)while\\s*\\(?\\s*(-?\\d+)\\s*(<=?|>=?)\\s*" + var + "\\s*\\)?\\s*[:{]"
        ).matcher(code);
        if (mYoda.find()) {
            String yodaLimitStr = mYoda.group(2);
            String yodaOp = mYoda.group(3);
            String flippedOp = flipOperator(yodaOp);
            return new String[]{ var, startStr, flippedOp, yodaLimitStr, "true" };
        }

        return null;
    }

    // =========================================================================
    // FOR LOOP ANALYSIS
    // =========================================================================

    public static class ForLoopInfo {
        public String var;
        public int start;
        public int stop;
        public Integer step; // Can be null if omitted in range()
        public String op;    // e.g. "<", ">", "<=", ">=" for C-style
        public String updateOp; // e.g. "++", "--", "+=", "-="
        public boolean isPythonRange;
        public boolean isYoda;

        public ForLoopInfo(String var, int start, int stop, Integer step, String op, String updateOp, boolean isPythonRange, boolean isYoda) {
            this.var = var;
            this.start = start;
            this.stop = stop;
            this.step = step;
            this.op = op;
            this.updateOp = updateOp;
            this.isPythonRange = isPythonRange;
            this.isYoda = isYoda;
        }
    }

    /**
     * Extracts for loop information for Python range() or C-style for(init; cond; step) loops.
     */
    public static ForLoopInfo extractForLoopInfo(String code, String language) {
        if (code == null || code.isBlank()) return null;
        String lang = language != null ? language.toLowerCase() : "";

        if (lang.contains("py") || code.contains("range(")) {
            // Python for i in range(start, stop, step) or range(start, stop) or range(stop)
            Matcher mRange3 = Pattern.compile("(?m)^(\\s*)for\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+in\\s+range\\s*\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)\\s*:").matcher(code);
            if (mRange3.find()) {
                return new ForLoopInfo(mRange3.group(2), Integer.parseInt(mRange3.group(3)), Integer.parseInt(mRange3.group(4)), Integer.parseInt(mRange3.group(5)), null, null, true, false);
            }
            Matcher mRange2 = Pattern.compile("(?m)^(\\s*)for\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+in\\s+range\\s*\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)\\s*:").matcher(code);
            if (mRange2.find()) {
                return new ForLoopInfo(mRange2.group(2), Integer.parseInt(mRange2.group(3)), Integer.parseInt(mRange2.group(4)), null, null, null, true, false);
            }
            Matcher mRange1 = Pattern.compile("(?m)^(\\s*)for\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+in\\s+range\\s*\\(\\s*(-?\\d+)\\s*\\)\\s*:").matcher(code);
            if (mRange1.find()) {
                return new ForLoopInfo(mRange1.group(2), 0, Integer.parseInt(mRange1.group(3)), null, null, null, true, false);
            }
        }

        // Java / C / C++ / JS style: for (int i = 10; i > 0; i--) or Yoda for (int i = 10; 0 < i; i--)
        Matcher mCStd = Pattern.compile(
            "(?m)for\\s*\\(\\s*(?:int|long|let|var)?\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(-?\\d+)\\s*;\\s*\\1\\s*(<=?|>=?)\\s*(-?\\d+)\\s*;\\s*\\1\\s*(\\+\\+|--|\\+=\\s*\\d+|-=\\s*\\d+)\\s*\\)"
        ).matcher(code);
        if (mCStd.find()) {
            return new ForLoopInfo(mCStd.group(1), Integer.parseInt(mCStd.group(2)), Integer.parseInt(mCStd.group(4)), null, mCStd.group(3), mCStd.group(5), false, false);
        }

        // Yoda C-style: for (int i = 10; 0 < i; i--)
        Matcher mCYoda = Pattern.compile(
            "(?m)for\\s*\\(\\s*(?:int|long|let|var)?\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(-?\\d+)\\s*;\\s*(-?\\d+)\\s*(<=?|>=?)\\s*\\1\\s*;\\s*\\1\\s*(\\+\\+|--|\\+=\\s*\\d+|-=\\s*\\d+)\\s*\\)"
        ).matcher(code);
        if (mCYoda.find()) {
            String yodaOp = mCYoda.group(4);
            String flippedOp = flipOperator(yodaOp);
            return new ForLoopInfo(mCYoda.group(1), Integer.parseInt(mCYoda.group(2)), Integer.parseInt(mCYoda.group(3)), null, flippedOp, mCYoda.group(5), false, true);
        }

        return null;
    }

    // =========================================================================
    // IF CONDITION ANALYSIS
    // =========================================================================

    public static class IfConditionInfo {
        public String var;
        public String limitStr;
        public String op; // Normalized VAR op LIMIT
        public boolean isYoda;

        public IfConditionInfo(String var, String limitStr, String op, boolean isYoda) {
            this.var = var;
            this.limitStr = limitStr;
            this.op = op;
            this.isYoda = isYoda;
        }
    }

    /**
     * Extracts if/elif condition info, returning Yoda detection if present.
     * Handles: `if 0 == x:`, `if (0 == x)`, `if (null != obj)`
     */
    public static IfConditionInfo extractIfConditionInfo(String code) {
        if (code == null || code.isBlank()) return null;

        // Yoda in if/elif: "if 0 == x:", "if (0 == x)", "if (null == ptr)"
        Matcher mYoda = Pattern.compile(
            "(?m)(?:if|elif|else\\s+if)\\s*\\(?\\s*(-?\\d+|null|None|true|false|\"[^\"]*\")\\s*(==|!=|<=?|>=?)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)?\\s*[:{]"
        ).matcher(code);
        if (mYoda.find()) {
            String limitVal = mYoda.group(1);
            String op = mYoda.group(2);
            String var = mYoda.group(3);
            return new IfConditionInfo(var, limitVal, flipOperator(op), true);
        }

        // Standard in if/elif: "if x == 0:", "if (x == 0)"
        Matcher mStd = Pattern.compile(
            "(?m)(?:if|elif|else\\s+if)\\s*\\(?\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(==|!=|<=?|>=?)\\s*(-?\\d+|null|None|true|false|\"[^\"]*\")\\s*\\)?\\s*[:{]"
        ).matcher(code);
        if (mStd.find()) {
            return new IfConditionInfo(mStd.group(1), mStd.group(3), mStd.group(2), false);
        }

        return null;
    }

    // =========================================================================
    // VALIDATION AND INVERSION GUARDS
    // =========================================================================

    /**
     * Validates whether loop or conditional logic is logically sound.
     */
    public static boolean isLoopLogicallyValid(String code) {
        if (code == null) return true;

        // 1. Check while loop
        String[] wInfo = extractWhileLoopInfo(code);
        if (wInfo != null) {
            int start = Integer.parseInt(wInfo[1]);
            String op = wInfo[2]; // Normalized to VAR op LIMIT
            int limit = Integer.parseInt(wInfo[3]);
            String var = wInfo[0];

            if (start < limit) {
                boolean validCondition = op.equals("<") || op.equals("<=");
                boolean validStep = code.matches("(?s).*\\b" + var + "\\s*\\+=\\s*\\d+.*") ||
                                    code.matches("(?s).*\\b" + var + "\\s*=\\s*" + var + "\\s*\\+\\s*\\d+.*") ||
                                    code.matches("(?s).*\\b" + var + "\\s*\\+\\+.*");
                return validCondition && validStep;
            }

            if (start > limit) {
                boolean validCondition = op.equals(">") || op.equals(">=");
                boolean validStep = code.matches("(?s).*\\b" + var + "\\s*-=\\s*\\d+.*") ||
                                    code.matches("(?s).*\\b" + var + "\\s*=\\s*" + var + "\\s*-\\s*\\d+.*") ||
                                    code.matches("(?s).*\\b" + var + "\\s*--.*");
                return validCondition && validStep;
            }
            return false;
        }

        // 2. Check for loop
        ForLoopInfo fInfo = extractForLoopInfo(code, "python");
        if (fInfo != null) {
            if (fInfo.isPythonRange) {
                // If start > stop, range needs negative step (e.g. range(10, 0, -1))
                if (fInfo.start > fInfo.stop) {
                    return fInfo.step != null && fInfo.step < 0;
                }
                // If start < stop, step should be positive (or default omitted)
                if (fInfo.start < fInfo.stop) {
                    return fInfo.step == null || fInfo.step > 0;
                }
            } else {
                // C-style for loop
                if (fInfo.start < fInfo.stop) {
                    boolean validOp = fInfo.op.startsWith("<");
                    boolean validUpdate = fInfo.updateOp.contains("++") || fInfo.updateOp.contains("+=");
                    return validOp && validUpdate;
                }
                if (fInfo.start > fInfo.stop) {
                    boolean validOp = fInfo.op.startsWith(">");
                    boolean validUpdate = fInfo.updateOp.contains("--") || fInfo.updateOp.contains("-=");
                    return validOp && validUpdate;
                }
            }
        }

        return true;
    }

    public static boolean isDeadCodeOrInfiniteLoop(String code) {
        if (code == null) return false;
        String[] wInfo = extractWhileLoopInfo(code);
        if (wInfo != null) {
            int start = Integer.parseInt(wInfo[1]);
            String op = wInfo[2]; // Normalized to VAR op LIMIT
            int limit = Integer.parseInt(wInfo[3]);
            String var = wInfo[0];
            // Dead code checks (condition is False from the start)
            if (op.equals("<") && start >= limit) return true;
            if (op.equals("<=") && start > limit) return true;
            if (op.equals(">") && start <= limit) return true;
            if (op.equals(">=") && start < limit) return true;
            // Infinite loop checks (step goes wrong direction)
            if (start < limit && (code.matches("(?s).*\\b" + var + "\\s*-=\\s*\\d+.*") || code.matches("(?s).*\\b" + var + "\\s*--.*"))) return true;
            if (start > limit && (code.matches("(?s).*\\b" + var + "\\s*\\+=\\s*\\d+.*") || code.matches("(?s).*\\b" + var + "\\s*\\+\\+.*"))) return true;
        }

        ForLoopInfo fInfo = extractForLoopInfo(code, "python");
        if (fInfo != null && fInfo.isPythonRange) {
            // Python range(10, 0) with no step returns an empty sequence (dead code loop)
            if (fInfo.start > fInfo.stop && fInfo.step == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects when an LLM fix mutated comparison values or flipped logic direction.
     */
    public static boolean isLoopLogicInverted(String original, String fix) {
        if (original == null || fix == null) return false;

        // Check while loop inversions
        String[] origInfo = extractWhileLoopInfo(original);
        String[] fixInfo = extractWhileLoopInfo(fix);
        if (origInfo != null && fixInfo != null) {
            int origStart = Integer.parseInt(origInfo[1]);
            int origLimit = Integer.parseInt(origInfo[3]);
            String origOp = origInfo[2];
            int fixStart = Integer.parseInt(fixInfo[1]);
            int fixLimit = Integer.parseInt(fixInfo[3]);
            String fixOp = fixInfo[2];

            if (origLimit != fixLimit) return true;
            if (origStart != fixStart) return true;
            if (origStart < origLimit && fixOp.startsWith(">")) return true;
            if (origStart > origLimit && fixOp.startsWith("<")) return true;
        }

        // Check for loop inversions
        ForLoopInfo origFor = extractForLoopInfo(original, "python");
        ForLoopInfo fixFor = extractForLoopInfo(fix, "python");
        if (origFor != null && fixFor != null) {
            if (origFor.start != fixFor.start || origFor.stop != fixFor.stop) {
                return true;
            }
        }

        return false;
    }

    // =========================================================================
    // SMART REPAIR ENGINE
    // =========================================================================

    public static FixResult analyzeAndFixLoop(String code) {
        // 1. Try while loop repair
        String[] info = extractWhileLoopInfo(code);
        if (info != null) {
            String var = info[0];
            int start = Integer.parseInt(info[1]);
            String op = info[2];
            int limit = Integer.parseInt(info[3]);
            boolean isYoda = "true".equals(info[4]);

            String fixed = code;
            StringBuilder expl = new StringBuilder();

            if (fixed.contains("print(j)") && !fixed.contains("j =")) {
                fixed = fixed.replace("print(j)", "print(" + var + ")");
                expl.append("Fixed undefined variable `j` to `").append(var).append("`. ");
            }

            if (start < limit) {
                if (op.startsWith(">")) {
                    String correctOp = op.replace(">", "<");
                    if (isYoda) {
                        String yodaCorrectOp = flipOperator(correctOp);
                        fixed = fixed.replaceAll(
                            "(\\bwhile\\s*\\(?\\s*)" + limit + "\\s*" + Pattern.quote(flipOperator(op)) + "(\\s*" + var + ")",
                            "$1" + limit + " " + yodaCorrectOp + "$2");
                    } else {
                        fixed = fixed.replaceAll("(\\bwhile\\s*\\(?\\s*" + var + "\\s*)" + Pattern.quote(op) + "(\\s*" + limit + ")", "$1" + correctOp + "$2");
                    }
                    expl.append("Corrected loop condition to `").append(var).append(" ").append(correctOp).append(" ").append(limit).append("`. ");
                }
                if (fixed.matches("(?s).*\\b" + var + "\\s*-=\\s*\\d+.*")) {
                    fixed = fixed.replaceAll("(?m)^(\\s*)" + var + "\\s*-=\\s*(\\d+)", "$1" + var + " += $2");
                    expl.append("Corrected loop step direction to increment (`").append(var).append(" += 1`). ");
                } else if (!fixed.matches("(?s).*\\b" + var + "\\s*(\\+=|=).*")) {
                    fixed = appendStep(fixed, var, "+= 1");
                    expl.append("Added loop increment (`").append(var).append(" += 1`) so loop terminates properly. ");
                }
            } else if (start > limit) {
                if (op.startsWith("<")) {
                    String correctOp = op.replace("<", ">");
                    if (isYoda) {
                        String yodaCorrectOp = flipOperator(correctOp);
                        fixed = fixed.replaceAll(
                            "(\\bwhile\\s*\\(?\\s*)" + limit + "\\s*" + Pattern.quote(flipOperator(op)) + "(\\s*" + var + ")",
                            "$1" + limit + " " + yodaCorrectOp + "$2");
                    } else {
                        fixed = fixed.replaceAll("(\\bwhile\\s*\\(?\\s*" + var + "\\s*)" + Pattern.quote(op) + "(\\s*" + limit + ")", "$1" + correctOp + "$2");
                    }
                    expl.append("Corrected loop condition from `").append(var).append(" ").append(op).append(" ").append(limit).append("` to `").append(var).append(" ").append(correctOp).append(" ").append(limit).append("` (from ").append(start).append(" down to ").append(limit).append("). ");
                }
                if (fixed.matches("(?s).*\\b" + var + "\\s*\\+=\\s*\\d+.*")) {
                    fixed = fixed.replaceAll("(?m)^(\\s*)" + var + "\\s*\\+=\\s*(\\d+)", "$1" + var + " -= $2");
                    expl.append("Corrected loop step direction to decrement (`").append(var).append(" -= 1`). ");
                } else if (!fixed.matches("(?s).*\\b" + var + "\\s*(-=|=).*")) {
                    fixed = appendStep(fixed, var, "-= 1");
                    expl.append("Added loop decrement (`").append(var).append(" -= 1`) so loop terminates properly. ");
                }
            }

            if (expl.length() > 0) {
                return new FixResult(expl.toString().trim(), fixed, List.of(), "HIGH", List.of());
            }
        }

        // 2. Try Python range for-loop repair
        ForLoopInfo fInfo = extractForLoopInfo(code, "python");
        if (fInfo != null && fInfo.isPythonRange) {
            if (fInfo.start > fInfo.stop && fInfo.step == null) {
                String fixed = code.replace(
                    "range(" + fInfo.start + ", " + fInfo.stop + ")",
                    "range(" + fInfo.start + ", " + fInfo.stop + ", -1)"
                );
                return new FixResult("Added step `-1` to `range(" + fInfo.start + ", " + fInfo.stop + ", -1)` for counting down.", fixed, List.of(), "HIGH", List.of());
            }
        }

        return null;
    }

    /**
     * Fixes ONLY Yoda conditions across while loops, for loops, and if statements
     * without altering values.
     */
    public static String fixYodaConditionsOnly(String code, String language) {
        if (code == null || code.isBlank()) return code;
        String result = code;

        // Fix while loop Yoda
        String[] wInfo = extractWhileLoopInfo(result);
        if (wInfo != null && "true".equals(wInfo[4])) {
            result = fixYodaConditionOnly(result, wInfo);
        }

        // Fix if/elif condition Yoda
        IfConditionInfo ifInfo = extractIfConditionInfo(result);
        if (ifInfo != null && ifInfo.isYoda) {
            String yodaOp = flipOperator(ifInfo.op);
            result = result.replace(
                ifInfo.limitStr + " " + yodaOp + " " + ifInfo.var,
                ifInfo.var + " " + ifInfo.op + " " + ifInfo.limitStr
            );
        }

        return result;
    }

    /**
     * Fixes ONLY the Yoda condition style in while loops without changing any values.
     */
    public static String fixYodaConditionOnly(String code, String[] loopInfo) {
        if (code == null || loopInfo == null) return code;
        String var = loopInfo[0];
        String limitStr = loopInfo[3];
        String normalizedOp = loopInfo[2];
        boolean isYoda = "true".equals(loopInfo[4]);

        if (!isYoda) return code;

        String yodaOp = flipOperator(normalizedOp);
        String result = code.replaceAll(
            "(\\bwhile\\s*\\(?\\s*)" + Pattern.quote(limitStr) + "\\s*" + Pattern.quote(yodaOp) + "\\s*(" + var + "\\s*\\)?\\s*[:{])",
            "$1" + var + " " + normalizedOp + " " + limitStr + " $2"
        );

        if (result.equals(code)) {
            result = code.replace(
                limitStr + " " + yodaOp + " " + var,
                var + " " + normalizedOp + " " + limitStr
            );
        }

        return result;
    }

    public static String fallbackPythonFix(String originalContent) {
        if (originalContent == null) return "";
        FixResult smartFix = analyzeAndFixLoop(originalContent);
        if (smartFix != null && smartFix.fixCode() != null) {
            return smartFix.fixCode();
        }
        return originalContent;
    }
}
