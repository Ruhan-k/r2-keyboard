"""Furry Conversation Starter topic library.

These are TOPIC SEEDS, not finished questions. The language model turns a few
randomly chosen seeds into fresh casual DM questions on every request, so the
same seed never produces the same wording twice.

STARTER_TOPICS maps a category slug -> list of seed ideas.
CATEGORY_TAGS maps a category slug -> tags used to favour categories for a
given reply intent (ARTIST wants art, RUDE wants hot takes, ...).
KNOWN_FACT_RULES lets the backend avoid asking something the client's saved
memory already answers.
"""

from __future__ import annotations

import re

STARTER_TOPICS: dict[str, list[str]] = {
    "sona_species": [
        "a species they would never make a sona of",
        "an underrated species that deserves way more sonas",
        "their dream sona if they could pick any species",
        "a weird hybrid or species mix they would actually want to see",
        "ranking their top three species and defending the order",
        "whether their species matches their real personality",
        "an unusual or rare species they think is secretly cool",
        "a species they got tired of seeing everywhere",
        "their least favorite species and a petty reason why",
        "a species they thought was mid until one design changed their mind",
    ],
    "sona_names": [
        "how they pick names for sonas and characters",
        "a sona name they now find embarrassing",
        "naming a sona after something completely dumb",
        "whether a name or a design comes first for them",
        "a name they love but have never been able to use",
    ],
    "sona_lore": [
        "the backstory of their sona or favorite character",
        "what powers or abilities their sona would have in canon",
        "what universe or world their sona lives in",
        "their sona's job or occupation",
        "what their sona's voice would sound like",
        "their sona's biggest flaw",
        "what their sona does on a lazy weekend",
    ],
    "sona_look": [
        "the color palette of their sona and why those colors",
        "markings or patterns they always add to designs",
        "an accessory their sona is never seen without",
        "their sona's everyday outfit versus their dressed up outfit",
        "how tall their sona is compared to other sonas",
        "a seasonal or Halloween version of their sona",
        "which feature they always spend the most time on: ears, tail, paws, horns or wings",
        "their sona's default facial expression",
        "a pose that feels most like their sona",
    ],
    "sona_history": [
        "their very first sona and how it looked",
        "a redesign they instantly regretted",
        "an old sona design they find embarrassing now",
        "whether they have alternate sonas or side characters",
        "how their sona changed over the years",
        "what they would redesign first if they got one redesign for free",
    ],
    "art_styles": [
        "which furry art styles hit hardest for them",
        "cartoony versus realistic furry art",
        "a style they wish they could draw in",
        "an art trend they are tired of seeing",
        "a furry art trend they actually love",
        "chibi versus full proportion designs",
    ],
    "artists": [
        "their favorite furry artist right now",
        "an artist they discovered by accident and instantly followed",
        "an underrated artist they think needs more attention",
        "which artist's style they would trade a kidney for",
        "whether they follow artists for the art or the personality",
    ],
    "drawing": [
        "whether they draw themselves and how long they have been at it",
        "the character or species they find hardest to draw",
        "how they warm up before drawing",
        "the worst drawing slump they went through",
        "a drawing they are secretly proud of",
        "what they draw when they are bored",
    ],
    "commissions": [
        "their dream commission if budget did not matter",
        "the best commission experience they ever had",
        "what they would commission next",
        "badge versus icon versus headshot versus full body preference",
        "how they pick which artist to commission",
        "whether they prefer reference sheets or single illustrations",
        "the funniest commission request they have seen",
    ],
    "art_trades": [
        "doing art trades or collabs and what they would want to trade for",
        "adoptables: love them or too much stress",
        "custom characters versus adoptables",
        "a collab idea with two sonas meeting for the first time",
        "gifting art to friends and what they would draw for someone",
    ],
    "fursuits": [
        "their dream fursuit design",
        "partial suit versus full suit",
        "suit makers they admire",
        "whether they would fursuit and how they would handle the heat",
        "what makes a suit look good or off to them",
        "what they would do first if they were in suit for a day",
        "a suit detail that would be a dealbreaker for them",
    ],
    "conventions": [
        "a dream convention they would love to attend",
        "a funny or chaotic convention story",
        "online fandom versus meeting people at conventions",
        "what they would pack for a first convention",
        "what they are most nervous about at a convention",
    ],
    "videos": [
        "furry YouTube channels they watch",
        "furry TikTok trends they secretly like",
        "furry animation they could rewatch forever",
        "a furry video that lives rent free in their head",
        "what kind of furry content they wish existed",
    ],
    "memes": [
        "the funniest furry meme they have seen lately",
        "a meme format that fits their sona perfectly",
        "furry humor that only the fandom would understand",
        "a cursed furry meme they cannot unsee",
    ],
    "games": [
        "VRChat worlds or avatars they love",
        "the avatar they would build for themselves",
        "games where they would make a furry character",
        "a game they wish had furry customization",
        "gaming sessions with furry friends and what they play",
    ],
    "community": [
        "how they first discovered the furry fandom",
        "their favorite Discord server vibe",
        "stereotypes about the fandom that are just wrong",
        "the fandom friend who changed their experience the most",
        "furry nostalgia: something from the older days of the fandom",
        "how the fandom has changed since they joined",
        "light fandom drama or trends they have noticed",
    ],
    "hot_takes": [
        "an unpopular furry opinion they will defend",
        "a hot take about a certain species or design",
        "a design trend they think is overrated",
        "something everyone in the fandom likes that they do not",
        "a cursed design they cannot stop thinking about",
        "a design that is way cuter than it has any right to be",
    ],
    "hypotheticals": [
        "a would you rather with two ridiculous sona related options",
        "a random hypothetical: their sona in an ordinary job",
        "what their sona would do in a zombie apocalypse",
        "which sona they would swap with for one day",
        "what happens if their sona meets their friend's sona",
        "which species would win a dumb made up competition",
        "if they could add one real animal trait to their sona",
    ],
    "oc_lore": [
        "their OC's relationships and who hangs with who",
        "what their OC does for a living",
        "what superpower their OC would pick",
        "the universe or setting their OCs share",
        "a song or playlist that matches their OC",
        "which character they are the most attached to",
        "a character they planned but never got to draw",
    ],
    "aus": [
        "their sona in a cyberpunk AU",
        "their sona in a medieval fantasy AU",
        "a Pokemon inspired version of their sona",
        "their sona as a monster or creature design",
        "a space or sci fi version of their sona",
        "a modern high school AU for their characters",
    ],
    "species_deep_dives": [
        "protogens: why they are so popular",
        "sergals and what makes them fun",
        "wolves being a classic pick",
        "foxes: overrated or still great",
        "dragons and how to make them feel original",
        "cats versus dogs in the fandom",
        "big cats like lions and tigers",
        "reptiles and lizards as sonas",
        "aquatic sonas like sharks and otters",
        "bird sonas and how hard feathers are",
        "fantasy species like griffins or hybrids",
        "an original species someone should invent",
    ],
    "merch": [
        "furry merch they own or want",
        "stickers or badges they would put on everything",
        "a wallpaper or phone background with their sona",
        "their profile picture and the story behind it",
        "something they would put on a plushie",
        "an enamel pin or charm design they would make",
    ],
    "social": [
        "which social apps they use for furry stuff",
        "their favorite furry creators to follow",
        "what they post when they feel creative",
        "the strangest place they found a furry artist",
        "whether they prefer posting or lurking",
    ],
    "music_vibes": [
        "a song that matches their sona perfectly",
        "a playlist they would make for their character",
        "what theme music their sona walks in to",
        "what song they associate with their favorite art",
    ],
    "personality": [
        "what their sona is like when they are tired",
        "whether their sona is more chaotic or chill",
        "what hobby their sona would be obsessed with",
        "which side of their personality their sona is based on",
    ],
    "creature_design": [
        "tails: long fluffy or short and stubby",
        "ears: pointy versus floppy",
        "paws and the details that make them feel real",
        "horns and how to place them well",
        "wings and when they look good or too much",
        "expressions and what makes a face feel alive",
        "poses that make a character feel alive",
        "creature design: what makes an original species believable",
    ],
    "dream_stuff": [
        "a dream artist they would love to work with",
        "their dream fursuit maker or illustrator",
        "a dream collab or project they would plan",
        "something fandom related they want to try this year",
    ],
}

# Tags let a reply intent favour matching categories.
CATEGORY_TAGS: dict[str, set[str]] = {
    "sona_species": {"sona", "species"},
    "sona_names": {"sona"},
    "sona_lore": {"sona", "lore"},
    "sona_look": {"sona", "design", "art", "cute"},
    "sona_history": {"sona", "nostalgia"},
    "art_styles": {"art", "design"},
    "artists": {"art"},
    "drawing": {"art"},
    "commissions": {"art", "commission"},
    "art_trades": {"art", "social"},
    "fursuits": {"design", "fun", "cute"},
    "conventions": {"fun", "social"},
    "videos": {"fun", "social"},
    "memes": {"fun", "funny"},
    "games": {"fun", "social"},
    "community": {"social", "nostalgia"},
    "hot_takes": {"hot", "funny"},
    "hypotheticals": {"funny", "fun"},
    "oc_lore": {"lore", "art"},
    "aus": {"lore", "design", "funny"},
    "species_deep_dives": {"species", "hot", "design"},
    "merch": {"cute", "art"},
    "social": {"social"},
    "music_vibes": {"cute", "lore"},
    "personality": {"sona", "cute"},
    "creature_design": {"design", "art"},
    "dream_stuff": {"cute", "art"},
}

# Which tags each reply intent favours when picking starter categories.
INTENT_TAGS: dict[str, set[str]] = {
    "AUTO": set(),
    "CHILL": {"social", "fun"},
    "FUNNY": {"funny", "fun"},
    "CUTE": {"cute"},
    "DRY": {"sona", "species"},
    "FLIRTY": {"cute", "fun"},
    "ARTIST": {"art", "design", "commission"},
    "RUDE": {"hot", "funny"},
}

# Categories whose answer can already be known from saved client memory.
# memory_key matches a saved memory's key/value; question matches a starter that
# would ask for the same fact again.
KNOWN_FACT_RULES: dict[str, dict[str, re.Pattern[str]]] = {
    "species": {
        "memory": re.compile(r"species|sona_species|animal", re.I),
        "question": re.compile(r"\b(what|which)\b[^?]{0,30}\b(species|animal)\b|\bwhat\s+are\s+(u|you)\s+(as\s+a|a)\b", re.I),
        "categories": {"sona_species"},
    },
    "name": {
        "memory": re.compile(r"sona_name|character_name|sona name", re.I),
        "question": re.compile(r"\b(what'?s|whats|what\s+is)\b[^?]{0,25}\b(name)\b", re.I),
        "categories": {"sona_names"},
    },
    "colors": {
        "memory": re.compile(r"color|colour|palette", re.I),
        "question": re.compile(r"\b(what|which)\b[^?]{0,25}\b(colou?rs?)\b", re.I),
        "categories": set(),
    },
    "artist": {
        "memory": re.compile(r"(fav|favorite|favourite).{0,12}artist", re.I),
        "question": re.compile(r"\b(fav(orite)?|favourite)\s+(furry\s+)?artist\b", re.I),
        "categories": {"artists"},
    },
}


def total_seed_count() -> int:
    return sum(len(seeds) for seeds in STARTER_TOPICS.values())


def seed_pairs() -> list[tuple[str, str]]:
    return [(category, seed) for category, seeds in STARTER_TOPICS.items() for seed in seeds]
