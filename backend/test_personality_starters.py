"""RUDE intent, texting-style rules, emoji/slang variety, AI-identity guard and the Starter feature.

Like test_backend.py these use a simulated Groq server and never a live key.
"""

import json
import random
import re
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

import main
import starter_topics

REAL_ASYNC_CLIENT = httpx.AsyncClient
PROJECT_ROOT = Path(__file__).resolve().parent.parent

SIX = ["c1 a", "c2 b", "c3 c", "c4 d", "c5 e", "c6 f"]


def completion(data):
    return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(data)}}]})


class Base(unittest.TestCase):
    def setUp(self):
        patcher = patch("main.configuration", return_value=("test-only-key", main.DEFAULT_MODEL))
        patcher.start()
        self.addCleanup(patcher.stop)
        self.client = TestClient(main.app)
        self.addCleanup(self.client.close)
        self.system_prompts = []

    def post(self, endpoint, body, data):
        def provider(request):
            payload = json.loads(request.content)
            self.system_prompts.append(payload["messages"][0]["content"])
            return completion(data)

        transport = httpx.MockTransport(provider)
        with patch("main.httpx.AsyncClient", side_effect=lambda **kw: REAL_ASYNC_CLIENT(transport=transport, **kw)):
            return self.client.post(endpoint, json=body)

    def reply(self, body, replies=("nah thats dumb", "ur pushing it", "ok no")):
        body = {"message": "i think pineapple pizza is elite", **body}
        return self.post("/generate-replies", body, {"candidates": list(replies) + ["x one", "y two", "z three"], "best_three": list(replies)})

    def starters(self, body=None, candidates=None):
        candidates = candidates or [
            "ur sona ever get stuck in a doorway lol",
            "okay random whats a species u cant stand",
            "who draws the cutest paws in ur opinion",
            "would u rather have wings or a second tail",
            "whats the most cursed fursuit idea u have seen",
            "do u mostly lurk or post",
        ]
        return self.post("/generate-starters", body or {}, {"starters": candidates})


class RudeIntentTests(Base):
    def test_rude_intent_is_valid_and_reaches_prompt(self):
        self.assertIn("RUDE", main.REPLY_INTENTS)
        response = self.reply({"replyIntent": "RUDE"})
        self.assertEqual(response.status_code, 200)
        system = self.system_prompts[0]
        self.assertIn("Intent: RUDE", system)
        for phrase in ("sarcastic", "dismissive", "skeptical", "roast", "No threats, no slurs"):
            self.assertIn(phrase, system)
        self.assertIn("serious", main.INTENT_GUIDANCE["RUDE"])

    def test_model_is_allowed_to_disagree_and_be_negative(self):
        self.reply({})
        system = self.system_prompts[0]
        self.assertIn("NOT a cheerleader", system)
        for phrase in ("disagree", "dislike something", "reject a suggestion", "unimpressed", "sound a little annoyed"):
            self.assertIn(phrase, system)
        self.assertIn("Penalize reflexive positivity", system)


class TextingStyleTests(Base):
    def test_naturalness_beats_grammar_and_punctuation_rules(self):
        self.reply({})
        system = self.system_prompts[0]
        self.assertIn("Naturalness is more important than correct grammar", system)
        self.assertIn("mostly lowercase", system)
        self.assertIn("NO period at the end of short messages", system)
        self.assertIn("do not use semicolons", system)
        self.assertIn("do not automatically capitalize", system)
        self.assertIn("apostrophes are optional", system)

    def test_slang_guidance_is_vocabulary_not_a_checklist(self):
        self.reply({})
        system = self.system_prompts[0]
        for word in ("deadass", "caught in 4k", "skill issue", "frfr", "goated", "canon event"):
            self.assertIn(word, system)
        self.assertIn("NOT a checklist", system)
        self.assertIn("Never stuff slang", system)

    def test_punctuation_and_energy_mirroring(self):
        self.reply({"message": "you coming today"})
        self.assertIn("barely any punctuation", self.system_prompts[0])
        self.assertIn("little or no punctuation", self.system_prompts[0])
        self.reply({"message": "WAIT WHATTTT???"})
        energetic = self.system_prompts[1]
        self.assertIn("ALL CAPS", energetic)
        self.assertIn("higher energy", energetic)

    def test_emoji_diversity_rule_reaches_prompt(self):
        self.reply({})
        system = self.system_prompts[0]
        self.assertIn("zero emoji is completely normal", system)
        self.assertIn("Do not default to", system)
        for emoji in ("🤨", "🗿", "🫠", "☠️"):
            self.assertIn(emoji, system)

    def test_recent_emoji_repetition_is_discouraged(self):
        recent = [
            {"senderType": "OUTGOING", "content": "bro what 😭", "timestamp": i}
            for i in range(1, 4)
        ]
        self.reply({"recentMessages": recent})
        system = self.system_prompts[0]
        avoid = next(line for line in system.splitlines() if line.startswith("AVOID EMOJI"))
        self.assertIn("😭", avoid)
        self.assertIn('started with "bro"', system)

    def test_no_avoid_line_when_emoji_were_not_repeated(self):
        recent = [{"senderType": "OUTGOING", "content": "yeah prob", "timestamp": 1}]
        self.reply({"recentMessages": recent})
        self.assertNotIn("AVOID EMOJI", self.system_prompts[0])

    def test_opener_and_slang_repetition_helpers(self):
        notes = main.format_recent_style_notes([
            main.ContextMessage(senderType="OUTGOING", content="brooo ngl thats wild", timestamp=1),
        ])
        self.assertIn('started with "bro"', notes)
        self.assertIn("ngl", notes)
        self.assertEqual(main.emojis_in("a 😵‍💫 b ☠️ c 😶‍🌫️"), ["😵‍💫", "☠️", "😶‍🌫️"])


class AiIdentityTests(Base):
    def test_bot_question_adds_guard_and_never_forces_ai_answer(self):
        self.reply({"message": "are u a bot? this sounds like ai lol"})
        system = self.system_prompts[0]
        self.assertIn("POSSIBLE AI/BOT QUESTION", system)
        self.assertIn("do not answer as the underlying language model", system)
        self.assertIn('never say or imply things like "I am an AI"', system.replace("\n", " ").replace("Never", "never"))
        self.assertIn("Do not invent proof", system)

    def test_ai_identity_replies_are_removed(self):
        response = self.reply(
            {"message": "are u a bot?"},
            replies=("as an ai i dont have feelings", "lmao what makes u say that", "nah just me"),
        )
        self.assertEqual(response.status_code, 200)
        replies = response.json()["replies"]
        self.assertEqual(len(replies), 3)
        self.assertFalse(any(main.leaks_ai_identity(r) for r in replies))

    def test_leak_detector(self):
        for text in ("I am an AI assistant", "as an AI i cant", "im just a bot", "i'm a language model"):
            self.assertTrue(main.leaks_ai_identity(text), text)
        for text in ("lmao ai art is mid", "nah its me", "that bot is so cute"):
            self.assertFalse(main.leaks_ai_identity(text), text)


class StarterTests(Base):
    def test_starter_returns_exactly_three_in_exact_shape(self):
        response = self.starters()
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(list(body.keys()), ["starters"])
        self.assertEqual(len(body["starters"]), 3)
        topics = response.headers["X-Starter-Topics"].split(",")
        self.assertEqual(len(topics), 3)
        self.assertTrue(all(t in starter_topics.STARTER_TOPICS or t == "memory_followup" for t in topics))

    def test_library_has_at_least_100_seeds(self):
        seeds = starter_topics.seed_pairs()
        self.assertGreaterEqual(len(seeds), 100)
        self.assertEqual(len({seed for _, seed in seeds}), len(seeds))
        self.assertGreaterEqual(len(starter_topics.STARTER_TOPICS), 25)
        self.assertEqual(set(starter_topics.CATEGORY_TAGS), set(starter_topics.STARTER_TOPICS))
        self.starters()
        self.assertIn("TOPIC SEEDS", self.system_prompts[0])

    def test_prompt_demands_casual_dms_not_interviews(self):
        self.starters()
        system = self.system_prompts[0]
        self.assertIn("NOT an interview question", system)
        self.assertIn("what inspired you to join", system)
        self.assertIn("Naturalness is more important than correct grammar", system)
        self.assertIn("NO period at the end of short messages", system)

    def test_recent_starters_are_excluded_and_sent_as_avoid_context(self):
        history = [
            {"text": "ur sona ever get stuck in a doorway lol", "topic": "sona_look"},
            {"text": "whats ur fav furry artist rn", "topic": "artists"},
        ]
        response = self.starters({"recentStarters": history})
        self.assertEqual(response.status_code, 200)
        system = self.system_prompts[0]
        self.assertIn("RECENT STARTERS TO AVOID", system)
        self.assertIn("whats ur fav furry artist rn", system)
        self.assertIn("Recently covered topics: artists, sona_look", system)
        out = response.json()["starters"]
        self.assertNotIn("ur sona ever get stuck in a doorway lol", out)
        self.assertEqual(len(out), 3)

    def test_recent_topics_are_not_picked_again(self):
        history = [main.StarterHistoryItem(text=f"q{i}", topic=t) for i, t in enumerate(
            ["artists", "drawing", "sona_species", "sona_names", "sona_lore", "sona_look"])]
        for seed in range(100):
            plan = main.plan_starters([], "AUTO", history, random.Random(seed))
            used = {category for category, _ in plan}
            self.assertFalse(used & {"artists", "drawing", "sona_species", "sona_names", "sona_lore", "sona_look"})
            self.assertEqual(len(plan), 6)
            self.assertEqual(len(used), 6)

    def test_known_client_facts_prevent_duplicate_questions(self):
        memories = [
            main.MemoryItem(key="sona_species", value="dragon", importance=8, category="STABLE"),
            main.MemoryItem(key="sona_name", value="Ember", importance=7, category="STABLE"),
        ]
        for seed in range(100):
            plan = main.plan_starters(memories, "AUTO", [], random.Random(seed))
            categories = {category for category, _ in plan}
            self.assertNotIn("sona_species", categories)
            self.assertNotIn("sona_names", categories)
            self.assertIn("memory_followup", categories)
        self.assertTrue(main.starter_asks_known_fact("wait what species is ur sona", memories))
        self.assertTrue(main.starter_asks_known_fact("whats ur sonas name", memories))
        self.assertFalse(main.starter_asks_known_fact("do dragons hoard stuff in ur lore", memories))
        body = {"memories": [{"key": "sona_species", "value": "dragon", "importance": 8, "category": "STABLE"}]}
        response = self.starters(body, candidates=[
            "wait what species is ur sona",
            "do u ever draw ur dragon in armor",
            "okay random whats ur fav furry artist rn",
            "ur first sona ever look like a mess too",
            "would u rather have wings or a second tail",
            "do u mostly lurk or post",
        ])
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("wait what species is ur sona", response.json()["starters"])
        system = self.system_prompts[0]
        self.assertIn("sona_species: dragon", system)
        self.assertIn("do not ask for these again", system)

    def test_three_starters_are_meaningfully_different(self):
        response = self.starters(candidates=[
            "what is ur favorite furry artist rn",
            "whats ur favorite furry artist rn lol",
            "what is ur favorite furry artist right now",
            "would u rather have wings or a second tail",
            "who is ur fav suit maker honestly",
            "do u mostly lurk or post",
        ])
        out = response.json()["starters"]
        self.assertEqual(len(out), 3)
        for i, left in enumerate(out):
            for right in out[i + 1:]:
                self.assertFalse(main.are_near_duplicates(left, right), (left, right))

    def test_too_few_valid_starters_is_a_retryable_error(self):
        response = self.starters(candidates=["same one"] * 6)
        self.assertEqual(response.status_code, 502)

    def test_starter_respects_reply_intent(self):
        for intent, phrase in (
            ("FUNNY", "absurd"), ("CUTE", "Warmer"), ("DRY", "Short and simple"),
            ("FLIRTY", "Lightly playful"), ("RUDE", "hot-take"), ("ARTIST", "art"), ("AUTO", "memory"),
        ):
            self.system_prompts.clear()
            response = self.starters({"replyIntent": intent})
            self.assertEqual(response.status_code, 200, intent)
            self.assertIn(f"STARTER MODE ({intent})", self.system_prompts[0])
            self.assertIn(phrase, self.system_prompts[0])

    def test_intent_favours_matching_categories(self):
        def share(intent, tag):
            hits = total = 0
            for seed in range(200):
                for category, _ in main.plan_starters([], intent, [], random.Random(seed)):
                    total += 1
                    hits += tag in starter_topics.CATEGORY_TAGS[category]
            return hits / total
        self.assertGreater(share("ARTIST", "art"), share("AUTO", "art") + 0.1)
        self.assertGreater(share("RUDE", "hot"), share("AUTO", "hot") + 0.05)

    def test_starters_never_force_commissions_or_ai_identity(self):
        self.starters()
        self.assertIn("Do not force a commission pitch", self.system_prompts[0])
        response = self.starters(candidates=["as an ai i like species talk"] + [
            "who draws the cutest paws in ur opinion", "do u mostly lurk or post", "ur first sona ever look like a mess too",
            "would u rather have wings or a second tail", "whats the weirdest species mix u would try",
        ])
        self.assertFalse(any(main.leaks_ai_identity(s) for s in response.json()["starters"]))


class AndroidWiringTests(unittest.TestCase):
    layout = PROJECT_ROOT / "app/src/main/res/layout/keyboard_view.xml"
    service = PROJECT_ROOT / "app/src/main/java/com/example/furryreply/FurryReplyInputMethodService.kt"
    api = PROJECT_ROOT / "app/src/main/java/com/example/furryreply/ReplyApi.kt"

    def setUp(self):
        if not self.layout.exists():
            self.skipTest("Android sources are not next to the backend")

    def test_starter_button_and_rude_intent_exist_in_layout(self):
        xml = self.layout.read_text(encoding="utf-8")
        self.assertIn('android:id="@+id/starter_button"', xml)
        self.assertIn("💬 Starter", xml)
        self.assertIn('android:id="@+id/intent_rude"', xml)
        order = [i for i in re.findall(r'android:id="@\+id/intent_(\w+)"', xml) if i not in ("scroll", "container")]
        self.assertEqual(order, ["auto", "chill", "funny", "cute", "dry", "flirty", "artist", "rude"])

    def test_intent_strip_and_starter_api_are_wired(self):
        service = self.service.read_text(encoding="utf-8")
        self.assertIn('listOf("AUTO", "CHILL", "FUNNY", "CUTE", "DRY", "FLIRTY", "ARTIST", "RUDE")', service)
        self.assertIn("starter_button", service)
        api = self.api.read_text(encoding="utf-8")
        self.assertIn('@POST("generate-starters")', api)


if __name__ == "__main__":
    unittest.main()
