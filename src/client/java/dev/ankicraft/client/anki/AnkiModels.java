package dev.ankicraft.client.anki;

import java.util.ArrayList;
import java.util.List;

public final class AnkiModels {
    private AnkiModels() {}

    public static class AnkiDeck {
        public long id;
        public String name;
        public int newCount;
        public int learnCount;
        public int reviewCount;

        public AnkiDeck() {}

        public AnkiDeck(long id, String name, int newCount, int learnCount, int reviewCount) {
            this.id = id;
            this.name = name;
            this.newCount = newCount;
            this.learnCount = learnCount;
            this.reviewCount = reviewCount;
        }

        public int totalDue() {
            return newCount + learnCount + reviewCount;
        }

        public int depth() {
            int count = 0;
            int idx = 0;
            while ((idx = name.indexOf("::", idx)) != -1) {
                count++;
                idx += 2;
            }
            return count;
        }

        public String shortName() {
            int last = name.lastIndexOf("::");
            return last >= 0 ? name.substring(last + 2) : name;
        }
    }

    public static class AnkiCard {
        public long cardId;
        public long noteId;
        public long deckId;
        public int cardType;
        public String deckName;
        public String questionHtml;
        public String answerHtml;
        public List<String> nextReviews = new ArrayList<>();
        public List<String> soundFiles = new ArrayList<>();

        public AnkiCard() {}

        public AnkiCard(long cardId, long noteId, String deckName, String questionHtml, String answerHtml, List<String> nextReviews) {
            this(cardId, noteId, 0L, 2, deckName, questionHtml, answerHtml, nextReviews, List.of());
        }

        public AnkiCard(long cardId, long noteId, long deckId, int cardType, String deckName, String questionHtml, String answerHtml, List<String> nextReviews, List<String> soundFiles) {
            this.cardId = cardId;
            this.noteId = noteId;
            this.deckId = deckId;
            this.cardType = cardType;
            this.deckName = deckName;
            this.questionHtml = questionHtml;
            this.answerHtml = answerHtml;
            if (nextReviews != null) {
                this.nextReviews = nextReviews;
            }
            if (soundFiles != null) {
                this.soundFiles = soundFiles;
            }
        }
    }

    public static class PendingReview {
        public long cardId;
        public int ease;
        public long timestampMs;

        public PendingReview() {}

        public PendingReview(long cardId, int ease, long timestampMs) {
            this.cardId = cardId;
            this.ease = ease;
            this.timestampMs = timestampMs;
        }
    }
}
