package ie.cadhan.gramadoir

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ie.cadhan.gramadoir.databinding.ActivityAboutBinding

// ---------------------------------------------------------------------------
// AboutActivity — displays information about the app, its APIs and licence
// ---------------------------------------------------------------------------
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarAbout)

        // Back arrow navigates back to MainActivity
        binding.toolbarAbout.setNavigationOnClickListener {
            finish()
        }
    }
}