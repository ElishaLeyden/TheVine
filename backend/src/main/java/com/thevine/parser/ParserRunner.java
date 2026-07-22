package com.thevine.parser;

public class ParserRunner {
    public static void main(String[] args) {
        String code = "@python\n# Python block\nnumbers = [1, 2, 3, 4, 5]\nname = \"TheVine User\"\nprint(\"Python: Created data\")\n@export(\"data\", numbers)\n@export(\"user\", name)\n\n@javascript\n// JavaScript block\nconst numbers = @import(\"data\")\nconst doubled = numbers.map(x => x * 2)\nconsole.log(\"JavaScript: Doubled numbers:\", doubled)\n@export(\"result\", doubled)\n\n@python\n# Back to Python\nfinal = @import(\"result\")\nprint(\"Python received:\", final)\n";

        CodeParser parser = new CodeParser();
        ParsedResult result = parser.parse(code);
        System.out.println("blocks=" + result.getBlocks().size());
        for (CodeBlock b : result.getBlocks()) {
            System.out.println("--- block language=" + b.getLanguage() + " ---");
            System.out.println(b.getCode());
            System.out.println("--- end block ---");
        }
        System.out.println("exports=" + result.getExports().size());
        System.out.println("imports=" + result.getImports().size());
    }
}
