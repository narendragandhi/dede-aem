# Video 2 -- "Works on real sites" (~50 s)
# "mysite" is WKND with its content modules removed -- what a real project repo looks like:
# the pages are authored in AEM, not committed. Output lines are from real runs.
WINDOW_TITLE = "dede — a site whose pages live in AEM"

SCENES = [
    ("title", "Real sites author their pages in AEM",
     "Not in Git. So which components does /content/site/en.html render?", 4.5),

    ("caption", "Code only: dede can't tell which components a page uses"),
    ("type", "dede mysite --request-log logs/request.log"),
    ("out", ["~...", "!Requests: 35 total, 0 attributed to project code (0 content page(s) indexed)"], 0.3),
    ("pause", 3.5),

    ("caption", "Add the site's content: a Package Manager export…"),
    ("type", "dede mysite --request-log logs/request.log --content mysite-content.zip"),
    ("out", ["~...",
             "Content: 292 node(s)/document(s) added from mysite-content.zip",
             "!Requests: 35 total, 32 attributed to project code (223 content page(s) indexed)",
             "  23 requests  com.adobe.aem.guides.wknd.core.models.impl.ImageListImpl",
             "  9 requests  com.adobe.aem.guides.wknd.core.models.impl.BylineImpl"], 0.35),
    ("caption", "0 → 32 of 35 requests attributed: the same result as with content in the repo"),
    ("pause", 5.0),

    ("caption", "…or read it from a running instance (credentials from the environment only)"),
    ("out", ["# --content https://author.example.com/content/mysite   (QueryBuilder, paged)",
             "# --content nodes.json                                (a saved QueryBuilder response)",
             "# DEDE_AEM_TOKEN or DEDE_AEM_USER / DEDE_AEM_PASSWORD"], 0.4),
    ("pause", 5.5),

    ("title", "Follows how Sling actually resolves",
     "Editable templates · experience fragments · resource supertypes\nvanity URLs · selectors and extensions", 5.0),
]
