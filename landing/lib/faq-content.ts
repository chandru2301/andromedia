import { siteConfig } from "@/lib/site-content";

export const faqMeta = {
  title: "FAQ",
  description:
    "Common questions about the Andro trial — data handling, Zoho access, question limits, and what happens after.",
};

export const faqPage = {
  eyebrow: "FAQ",
  heading: "Frequently asked questions",
  intro: "Short answers to the questions people actually ask during the trial.",
  docsCta: { label: "Read the docs", href: siteConfig.docsUrl },
  trialCta: { label: "Start trial", href: siteConfig.trialUrl },
};

export const faqItems = [
  {
    question: "Does my Zoho data leave my machine?",
    answer:
      "Your CRM data is indexed locally. Only the retrieved context is sent to the AI model for reasoning during your trial.",
  },
  {
    question: "Can I revoke access?",
    answer:
      "Yes, anytime — delete the Self Client in Zoho API Console.",
  },
  {
    question: "How many questions can I ask in the trial?",
    answer: "20 questions per trial session.",
  },
  {
    question: "What happens after the trial?",
    answer:
      "You can self-host Andro with your own setup for unlimited use, or reach out for extended access.",
  },
];
