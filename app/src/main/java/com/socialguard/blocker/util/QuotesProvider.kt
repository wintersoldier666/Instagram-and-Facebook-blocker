package com.quell.app.util

object QuotesProvider {

    private val quotes = listOf(
        "\"Almost everything will work again if you unplug it for a few minutes, including you.\" — Anne Lamott",
        "\"The most precious resource we all have is time.\" — Steve Jobs",
        "\"Comparison is the thief of joy.\" — Theodore Roosevelt",
        "\"Social media is training us to compare our lives, instead of appreciating everything we are.\" — Bill Murray",
        "\"Disconnect to reconnect.\"",
        "\"Don't mistake activity for achievement.\" — John Wooden",
        "\"The richest people in the world look for and build networks. Everyone else looks for work.\" — Robert Kiyosaki",
        "\"Your attention is the most valuable thing you have. Spend it wisely.\"",
        "\"Life is what happens when you're not scrolling.\"",
        "\"Be present in this moment, because this moment is your life.\" — Rumi",
        "\"Real life is happening right now, beyond the screen.\"",
        "\"You can't buy back your wasted time.\"",
        "\"Do not let the behavior of others destroy your inner peace.\" — Dalai Lama",
        "\"The best view comes after the hardest climb — not after the most scrolling.\"",
        "\"Stop comparing your behind-the-scenes to everyone else's highlight reel.\"",
        "\"Your phone is not your friend — it is a tool. Use it, don't let it use you.\"",
        "\"Every time you check your phone, you're making someone else's agenda more important than your own.\"",
        "\"Mindfulness is the awareness that arises when we pay attention, on purpose, in the present moment.\" — Jon Kabat-Zinn",
        "\"Solitude is where I place my chaos to rest and awaken my inner peace.\" — Nikki Rowe",
        "\"The quieter you become, the more you are able to hear.\" — Rumi"
    )

    private val facts = listOf(
        "Fact: The average person spends 2 hours 27 minutes on social media every day.",
        "Fact: Instagram was designed to be addictive — the 'pull to refresh' mimics slot machine mechanics.",
        "Fact: 210 million people worldwide suffer from internet and social media addiction.",
        "Fact: Scrolling through social media before bed delays your sleep by an average of 1 hour.",
        "Fact: Studies show that reducing social media use to 30 min/day reduces loneliness and depression.",
        "Fact: The average person will spend 5 years and 4 months of their lifetime on social media.",
        "Fact: Heavy social media use is linked to a 70% increase in anxiety in teenagers.",
        "Fact: Dopamine is released every time you get a 'like', creating a feedback loop similar to gambling.",
        "Fact: People who take social media breaks report feeling happier and more productive.",
        "Fact: In 2023, Facebook had 3 billion monthly active users — nearly 40% of Earth's population.",
        "Fact: The human attention span has dropped from 12 seconds (2000) to 8 seconds (2015) partly due to smartphones.",
        "Fact: 60% of people report feeling inadequate after browsing social media profiles.",
        "Fact: Instagram generates over \$20 billion in revenue per year — from your attention.",
        "Fact: Deleting social media apps from your phone for just one week improves wellbeing significantly.",
        "Fact: Reading a book for 6 minutes reduces stress by 68%, more than any amount of scrolling.",
        "Fact: Face-to-face interaction boosts immune system function and is linked to longer life spans.",
        "Fact: 70% of social media posts are things people want others to think, not what they actually feel.",
        "Fact: The average person checks their phone 96 times a day — once every 10 minutes.",
        "Fact: Social media companies employ hundreds of engineers whose only job is to maximize your engagement.",
        "Fact: Spending time in nature for just 20 minutes lowers cortisol (stress hormone) significantly."
    )

    private var quoteIndex = (quotes.indices).random()
    private var factIndex = (facts.indices).random()
    private var lastWasQuote = false

    fun getNext(): String {
        return if (!lastWasQuote) {
            lastWasQuote = true
            quoteIndex = (quoteIndex + 1) % quotes.size
            quotes[quoteIndex]
        } else {
            lastWasQuote = false
            factIndex = (factIndex + 1) % facts.size
            facts[factIndex]
        }
    }

    fun getRandomQuote(): String = quotes.random()
    fun getRandomFact(): String = facts.random()
}
